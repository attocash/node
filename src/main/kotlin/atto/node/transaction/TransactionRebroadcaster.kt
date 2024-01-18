package atto.node.transaction

import atto.node.AsynchronousQueueProcessor
import atto.node.CacheSupport
import atto.node.network.BroadcastNetworkMessage
import atto.node.network.BroadcastStrategy
import atto.node.network.InboundNetworkMessage
import atto.node.network.NetworkMessagePublisher
import atto.node.transaction.TransactionRebroadcaster.TransactionSocketAddressHolder
import atto.protocol.transaction.AttoTransactionPush
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoTransaction
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.net.InetSocketAddress
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
class TransactionRebroadcaster(private val messagePublisher: NetworkMessagePublisher) :
    AsynchronousQueueProcessor<TransactionSocketAddressHolder>(100.milliseconds), CacheSupport {
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

        holderMap.compute(transaction.hash) { _, v ->
            val holder = v ?: TransactionSocketAddressHolder(transaction)
            holder.add(message.socketAddress)
            holder
        }

        logger.trace { "Started monitoring transaction to rebroadcast. $transaction" }
    }

    @EventListener
    suspend fun process(event: TransactionValidated) {
        val transactionHolder = holderMap.remove(event.transaction.hash)!!
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

    override suspend fun poll(): TransactionSocketAddressHolder? = withContext(singleDispatcher) {
        transactionQueue.poll()
    }

    override suspend fun process(value: TransactionSocketAddressHolder) {
        val transaction = value.transaction
        logger.trace { "Transaction dequeued. $transaction" }
        val transactionPush = AttoTransactionPush(transaction)
        val exceptions = value.socketAddresses

        val message = BroadcastNetworkMessage(
            BroadcastStrategy.EVERYONE,
            exceptions,
            transactionPush,
        )

        messagePublisher.publish(message)
    }


    class TransactionSocketAddressHolder(val transaction: AttoTransaction) {
        val socketAddresses = HashSet<InetSocketAddress>()

        fun add(socketAddress: InetSocketAddress) {
            socketAddresses.add(socketAddress)
        }
    }

    override fun clear() {
        holderMap.clear()
        transactionQueue.clear()
    }

}