package cash.atto.node.transaction

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoTransaction
import cash.atto.node.AsynchronousQueueProcessor
import cash.atto.node.CacheSupport
import cash.atto.node.network.*
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.TransactionRebroadcaster.TransactionSocketAddressHolder
import cash.atto.protocol.transaction.AttoTransactionPush
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * This rebroadcaster aims to reduce data usage creating a list of nodes that already saw these transactions while
 * it waits for the internal validations.
 *
 * Once the account change is validated the transaction that triggered this change is added to the buffer and later
 * rebroadcasted.
 *
 */
@Service
class TransactionRebroadcaster(
    private val messagePublisher: NetworkMessagePublisher,
) : AsynchronousQueueProcessor<TransactionSocketAddressHolder>(100.milliseconds),
    CacheSupport {
    private val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val holderMap = ConcurrentHashMap<AttoHash, TransactionSocketAddressHolder>()
    private val transactionQueue: Deque<TransactionSocketAddressHolder> = LinkedList()

    @PreDestroy
    override fun stop() {
        singleDispatcher.cancel()
        super.stop()
    }

    @EventListener
    fun process(message: InboundNetworkMessage<AttoTransactionPush>) {
        val transaction = message.payload.transaction

        if (message.source == MessageSource.REST) {
            broadcast(transaction, emptySet())
            return
        }

        holderMap.compute(transaction.hash) { _, v ->
            val holder = v ?: TransactionSocketAddressHolder(transaction)
            holder.add(message.publicUri)
            holder
        }

        logger.trace { "Started monitoring transaction to rebroadcast. $transaction" }
    }

    @EventListener
    suspend fun process(event: TransactionValidated) {
        val transactionHolder = holderMap.remove(event.transaction.hash) ?: return
        withContext(singleDispatcher) {
            transactionQueue.add(transactionHolder)
            logger.trace { "Transaction queued for rebroadcast. ${event.transaction}" }
        }
    }

    @EventListener
    fun process(event: TransactionRejected) {
        holderMap.remove(event.transaction.hash)
        logger.trace { "Stopped monitoring transaction because it was rejected due to ${event.reason}. ${event.transaction}" }
    }

    @EventListener
    fun process(event: TransactionDropped) {
        holderMap.remove(event.transaction.hash)
        logger.trace { "Stopped monitoring transaction because event was dropped. ${event.transaction}" }
    }

    override suspend fun poll(): TransactionSocketAddressHolder? =
        withContext(singleDispatcher) {
            transactionQueue.poll()
        }

    override suspend fun process(value: TransactionSocketAddressHolder) {
        val transaction = value.transaction
        logger.trace { "Transaction dequeued. $transaction" }

        broadcast(transaction, value.publicUris)
    }

    private fun broadcast(
        transaction: AttoTransaction,
        exceptions: Set<URI>,
    ) {
        val transactionPush = AttoTransactionPush(transaction)
        val message =
            BroadcastNetworkMessage(
                BroadcastStrategy.EVERYONE,
                exceptions,
                transactionPush,
            )

        messagePublisher.publish(message)
    }

    class TransactionSocketAddressHolder(
        val transaction: AttoTransaction,
    ) {
        val publicUris = HashSet<URI>()

        fun add(publicUri: URI) {
            publicUris.add(publicUri)
        }
    }

    override fun clear() {
        holderMap.clear()
        transactionQueue.clear()
    }
}
