package cash.atto.node.transaction.priotization

import cash.atto.commons.AttoHash
import cash.atto.commons.PreviousSupport
import cash.atto.commons.ReceiveSupport
import cash.atto.node.CacheSupport
import cash.atto.node.DuplicateDetector
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountUpdated
import cash.atto.node.election.ElectionExpired
import cash.atto.node.election.ElectionStarted
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionDropped
import cash.atto.node.transaction.TransactionReceived
import cash.atto.node.transaction.toTransaction
import cash.atto.protocol.AttoTransactionPush
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import kotlin.time.Duration.Companion.seconds

@Service
class TransactionPrioritizer(
    properties: TransactionPrioritizationProperties,
    private val eventPublisher: EventPublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val queue = TransactionQueue(properties.groupMaxSize!!, 8)
    private val activeElections = HashSet<AttoHash>()
    private val buffer = HashMap<AttoHash, MutableSet<Transaction>>()
    private val duplicateDetector = DuplicateDetector<AttoHash>(60.seconds)

    @PreDestroy
    fun stop() {
        singleDispatcher.cancel()
    }

    @Scheduled(fixedDelay = 100)
    suspend fun process() {
        withContext(singleDispatcher) {
            do {
                val transaction = queue.poll()
                transaction?.let {
                    eventPublisher.publishSync(TransactionReceived(it))
                }
            } while (transaction != null)
        }
    }

    @EventListener
    suspend fun add(message: InboundNetworkMessage<AttoTransactionPush>) {
        val transaction = message.payload.transaction

        if (duplicateDetector.isDuplicate(transaction.hash)) {
            logger.trace { "Ignored duplicated $transaction" }
            return
        }

        add(transaction.toTransaction())
    }

    @EventListener
    suspend fun process(event: AccountUpdated) =
        withContext(singleDispatcher) {
            val hash = event.transaction.hash

            activeElections.remove(hash)

            val bufferedTransactions = buffer.remove(hash) ?: setOf()

            bufferedTransactions.forEach {
                logger.debug { "Unbuffered $it" }
                add(it)
            }
        }

    @EventListener
    suspend fun process(event: ElectionStarted) =
        withContext(singleDispatcher) {
            activeElections.add(event.transaction.hash)
        }

    @EventListener
    suspend fun process(event: ElectionExpired) =
        withContext(singleDispatcher) {
            val hash = event.transaction.hash
            activeElections.remove(hash)
            buffer.remove(hash)
        }

    suspend fun add(transaction: Transaction) =
        withContext(singleDispatcher) {
            val block = transaction.block
            if (block is PreviousSupport && activeElections.contains(block.previous)) {
                buffer(block.previous, transaction)
            } else if (block is ReceiveSupport && activeElections.contains(block.sendHash)) {
                buffer(block.sendHash, transaction)
            } else {
                val droppedTransaction = queue.add(transaction)
                if (droppedTransaction != null) {
                    eventPublisher.publish(TransactionDropped(droppedTransaction))
                }
                logger.debug { "Queued $transaction" }
            }
        }

    private fun buffer(
        hash: AttoHash,
        transaction: Transaction,
    ) {
        buffer.compute(hash) { _, v ->
            val set = v ?: HashSet()
            set.add(transaction)
            set
        }
        logger.debug { "Buffered until dependencies are confirmed. $transaction" }
    }

    fun getQueueSize(): Int = queue.getSize()

    fun getBufferSize(): Int = buffer.size

    override fun clear() {
        queue.clear()
        activeElections.clear()
        buffer.clear()
        duplicateDetector.clear()
    }
}
