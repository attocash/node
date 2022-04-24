package org.atto.node.transaction.priotization

import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.atto.commons.AttoHash
import org.atto.commons.AttoTransaction
import org.atto.commons.PreviousSupport
import org.atto.commons.ReceiveSupportBlock
import org.atto.node.EventPublisher
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.transaction.AttoTransactionDropped
import org.atto.node.transaction.AttoTransactionReceived
import org.atto.node.transaction.TransactionConfirmed
import org.atto.node.transaction.TransactionStaled
import org.atto.protocol.transaction.AttoTransactionPush
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
class TransactionPrioritizer(
    properties: TransactionPrioritizationProperties,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private lateinit var job: Job

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val queue = TransactionQueue(properties.groupMaxSize!!)
    private val activeElections = HashSet<AttoHash>()
    private val buffer = HashMap<AttoHash, MutableSet<AttoTransaction>>()

    private val hashCache: MutableMap<AttoHash, AttoHash> = Caffeine.newBuilder()
        .maximumSize(properties.cacheMaxSize!!.toLong())
        .build<AttoHash, AttoHash>()
        .asMap()

    fun getQueueSize(): Int {
        return queue.getSize()
    }

    fun getBufferSize(): Int {
        return buffer.size
    }

    @EventListener
    fun process(event: TransactionConfirmed) = runBlocking(singleDispatcher) {
        val hash = event.payload.hash

        activeElections.remove(hash)

        val bufferedTransactions = buffer.remove(hash) ?: setOf()

        bufferedTransactions.forEach {
            logger.trace { "Unbuffered $it" }
            add(it)
        }
    }

    @EventListener
    fun process(event: TransactionStaled) = runBlocking(singleDispatcher) {
        val hash = event.payload.hash
        activeElections.remove(hash)
        buffer.remove(hash)
    }

    @EventListener
    fun add(message: InboundNetworkMessage<AttoTransactionPush>) {
        val attoTransaction = message.payload.transaction

        if (hashCache.contains(attoTransaction.hash)) {
            logger.trace { "Ignored duplicated $attoTransaction" }
            return
        }

        runBlocking {
            add(attoTransaction)
        }
    }

    suspend fun add(transaction: AttoTransaction) = withContext(singleDispatcher) {
        val block = transaction.block
        if (block is PreviousSupport && activeElections.contains(block.previous)) {
            buffer(block.previous, transaction)
        } else if (block is ReceiveSupportBlock && activeElections.contains(block.sendHash)) {
            buffer(block.sendHash, transaction)
        } else {
            val droppedTransaction = queue.add(transaction)
            if (droppedTransaction != null) {
                eventPublisher.publish(AttoTransactionDropped(droppedTransaction))
            }
            logger.trace { "Queued $transaction" }
        }
    }

    private fun buffer(hash: AttoHash, transaction: AttoTransaction) {
        buffer.compute(hash) { _, v ->
            val set = v ?: HashSet()
            set.add(transaction)
            set
        }
        logger.trace { "Buffered $hash" }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        job = GlobalScope.launch(CoroutineName("transaction-prioritizer")) {
            while (isActive) {
                val transaction = withContext(singleDispatcher) {
                    queue.poll()
                }
                if (transaction != null) {
                    hashCache.putIfAbsent(transaction.hash, transaction.hash)
                    eventPublisher.publish(AttoTransactionReceived(transaction))
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

}