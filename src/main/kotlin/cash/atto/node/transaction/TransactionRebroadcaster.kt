package cash.atto.node.transaction

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoTransaction
import cash.atto.node.CacheSupport
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.protocol.AttoTransactionPush
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

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
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val broadcastQueue = BroadcastQueue()

    @EventListener
    fun process(message: InboundNetworkMessage<AttoTransactionPush>) {
        val transaction = message.payload.transaction

        broadcastQueue.seen(transaction, message.publicUri)

        logger.trace { "Started monitoring transaction to rebroadcast. $transaction" }
    }

    @EventListener
    suspend fun process(event: TransactionValidated) {
        if (broadcastQueue.enqueue(event.transaction.hash)) {
            logger.trace { "Transaction queued for rebroadcast. ${event.transaction}" }
        }
    }

    @EventListener
    fun process(event: TransactionRejected) {
        broadcastQueue.drop(event.transaction.hash)
        logger.trace { "Stopped monitoring transaction because it was rejected due to ${event.reason}. ${event.transaction}" }
    }

    @EventListener
    fun process(event: TransactionDropped) {
        broadcastQueue.drop(event.transaction.hash)
        logger.trace { "Stopped monitoring transaction because event was dropped. ${event.transaction}" }
    }

    @Scheduled(fixedDelay = 10)
    fun dequeue() {
        do {
            val (transaction, publicUris) = broadcastQueue.poll()
            transaction?.let {
                broadcast(it, publicUris)
            }
        } while (transaction != null)
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

    override fun clear() {
        broadcastQueue.clear()
    }
}

private class TransactionSocketAddressHolder(
    val transaction: AttoTransaction,
) {
    val publicUris = HashSet<URI>()

    fun add(publicUri: URI) {
        publicUris.add(publicUri)
    }
}

private class BroadcastQueue {
    private val holderMap = ConcurrentHashMap<AttoHash, TransactionSocketAddressHolder>()
    private val transactionQueue = Channel<TransactionSocketAddressHolder>(capacity = UNLIMITED)

    fun seen(
        transaction: AttoTransaction,
        publicUri: URI,
    ) {
        holderMap.compute(transaction.hash) { _, v ->
            val holder = v ?: TransactionSocketAddressHolder(transaction)
            holder.add(publicUri)
            holder
        }
    }

    fun drop(hash: AttoHash) {
        holderMap.remove(hash)
    }

    suspend fun enqueue(hash: AttoHash): Boolean {
        val holder = holderMap.remove(hash) ?: return false
        transactionQueue.send(holder)
        return true
    }

    fun poll(): Pair<AttoTransaction?, Set<URI>> {
        val result = transactionQueue.tryReceive()

        if (result.isSuccess) {
            val holder = result.getOrThrow()
            return holder.transaction to holder.publicUris
        }

        return null to emptySet()
    }

    fun clear() {
        holderMap.clear()
        do {
            val (transaction, _) = poll()
        } while (transaction != null)
    }
}
