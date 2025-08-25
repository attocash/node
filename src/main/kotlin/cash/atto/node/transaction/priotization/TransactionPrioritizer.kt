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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val mutex = Mutex()

    private val queue = TransactionQueue(properties.groupMaxSize!!, 8)
    private val activeElections = HashSet<AttoHash>()
    private val buffer = HashMap<AttoHash, MutableSet<Transaction>>()
    private val duplicateDetector = DuplicateDetector<AttoHash>(60.seconds)

    @Scheduled(fixedRateString = "\${atto.transaction.prioritization.frequency}")
    suspend fun process() {
        do {
            val transaction =
                mutex.withLock {
                    val activeElectionCount = activeElections.size
                    if (activeElectionCount >= 1000) {
                        logger.debug { "There are $activeElectionCount active elections. Skipping prioritization for now." }
                        return
                    }
                    queue.poll()
                }
            transaction?.let {
                logger.debug { "Dequeued $transaction" }
                eventPublisher.publish(TransactionReceived(it))
            }
        } while (transaction != null)
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
    suspend fun process(event: AccountUpdated) {
        val bufferedTransactions =
            mutex.withLock {
                val hash = event.transaction.hash

                activeElections.remove(hash)

                return@withLock buffer.remove(hash)?.toList() ?: emptyList()
            }

        bufferedTransactions.forEach {
            logger.debug { "Unbuffered $it" }
            add(it)
        }
    }

    @EventListener
    suspend fun process(event: ElectionStarted) =
        mutex.withLock {
            activeElections.add(event.transaction.hash)
        }

    @EventListener
    suspend fun process(event: ElectionExpired) =
        mutex.withLock {
            val hash = event.transaction.hash
            activeElections.remove(hash)
            buffer.remove(hash)
        }

    suspend fun add(transaction: Transaction) =
        mutex.withLock {
            val block = transaction.block
            if (block is PreviousSupport && activeElections.contains(block.previous)) {
                buffer(block.previous, transaction)
            } else if (block is ReceiveSupport && activeElections.contains(block.sendHash)) {
                buffer(block.sendHash, transaction)
            } else {
                val droppedTransaction = queue.add(transaction)
                if (droppedTransaction != null) {
                    logger.debug { "Dropped $droppedTransaction" }
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
