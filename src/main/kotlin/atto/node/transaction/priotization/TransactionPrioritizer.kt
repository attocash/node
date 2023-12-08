package atto.node.transaction.priotization

import atto.node.CacheSupport
import atto.node.DuplicateDetector
import atto.node.EventPublisher
import atto.node.election.ElectionExpired
import atto.node.network.InboundNetworkMessage
import atto.node.transaction.*
import atto.protocol.transaction.AttoTransactionPush
import cash.atto.commons.AttoHash
import cash.atto.commons.PreviousSupport
import cash.atto.commons.ReceiveSupportBlock
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class TransactionPrioritizer(
    properties: TransactionPrioritizationProperties,
    private val eventPublisher: EventPublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private lateinit var job: Job

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val queue = TransactionQueue(properties.groupMaxSize!!)
    private val activeElections = HashSet<AttoHash>()
    private val buffer = HashMap<AttoHash, MutableSet<Transaction>>()
    private val duplicateDetector = DuplicateDetector<AttoHash>()

    @PreDestroy
    fun preDestroy() {
        singleDispatcher.cancel()
    }

    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        job = GlobalScope.launch(CoroutineName(this.javaClass.simpleName)) {
            while (isActive) {
                val transaction = withContext(singleDispatcher) {
                    queue.poll()
                }
                if (transaction != null) {
                    eventPublisher.publish(TransactionReceived(transaction))
                } else {
                    delay(100)
                }
            }
        }
    }

    @PreDestroy
    fun stop() {
        job.cancel()
    }

    @EventListener
    suspend fun add(message: InboundNetworkMessage<AttoTransactionPush>) {
        val transaction = message.payload.transaction.toTransaction()

        if (duplicateDetector.isDuplicate(transaction.hash)) {
            logger.trace { "Ignored duplicated $transaction" }
            return
        }

        add(transaction)
    }

    @EventListener
    suspend fun process(event: TransactionSaved) = withContext(singleDispatcher) {
        val hash = event.transaction.hash

        activeElections.remove(hash)

        val bufferedTransactions = buffer.remove(hash) ?: setOf()

        bufferedTransactions.forEach {
            logger.trace { "Unbuffered $it" }
            add(it)
        }
    }

    @EventListener
    suspend fun process(event: ElectionExpired) = withContext(singleDispatcher) {
        val hash = event.transaction.hash
        activeElections.remove(hash)
        buffer.remove(hash)
    }

    suspend fun add(transaction: Transaction) = withContext(singleDispatcher) {
        val block = transaction.block
        if (block is PreviousSupport && activeElections.contains(block.previous)) {
            buffer(block.previous, transaction)
        } else if (block is ReceiveSupportBlock && activeElections.contains(block.sendHash)) {
            buffer(block.sendHash, transaction)
        } else {
            val droppedTransaction = queue.add(transaction)
            if (droppedTransaction != null) {
                eventPublisher.publish(TransactionDropped(droppedTransaction))
            }
            logger.trace { "Queued $transaction" }
        }
    }

    private fun buffer(hash: AttoHash, transaction: Transaction) {
        buffer.compute(hash) { _, v ->
            val set = v ?: HashSet()
            set.add(transaction)
            set
        }
        logger.trace { "Buffered until dependencies are confirmed. $transaction" }
    }

    fun getQueueSize(): Int {
        return queue.getSize()
    }

    fun getBufferSize(): Int {
        return buffer.size
    }

    override fun clear() {
        queue.clear()
        activeElections.clear()
        buffer.clear()
        duplicateDetector.clear()
    }

}