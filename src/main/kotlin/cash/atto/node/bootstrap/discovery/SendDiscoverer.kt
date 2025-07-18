package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.ReceiveSupport
import cash.atto.node.CacheSupport
import cash.atto.node.DuplicateDetector
import cash.atto.node.EventPublisher
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.bootstrap.TransactionStuck
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.toTransaction
import cash.atto.node.vote.Vote
import cash.atto.protocol.AttoTransactionRequest
import cash.atto.protocol.AttoTransactionResponse
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

@Component
class SendDiscoverer(
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val eventPublisher: EventPublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val peers = ConcurrentHashMap<AttoPublicKey, URI>()

    private val unknownHashCache =
        Caffeine
            .newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(100_000)
            .build<AttoHash, AttoHash>()
            .asMap()

    private val duplicateDetector = DuplicateDetector<AttoHash>(1.minutes)

    @EventListener
    fun add(nodeEvent: NodeConnected) {
        val node = nodeEvent.node
        peers[node.publicKey] = node.publicUri
    }

    @EventListener
    fun remove(nodeEvent: NodeDisconnected) {
        val node = nodeEvent.node
        peers.remove(node.publicKey)
    }

    @EventListener
    fun process(event: TransactionDiscovered) {
        process(event.reason, event.transaction, event.votes)
    }

    @EventListener
    fun process(event: TransactionStuck) {
        process(event.reason, event.transaction, listOf())
    }

    private fun process(
        reason: TransactionRejectionReason?,
        transaction: Transaction,
        votes: Collection<Vote>,
    ) {
        if (reason != TransactionRejectionReason.RECEIVABLE_NOT_FOUND) {
            return
        }

        val block = transaction.block as ReceiveSupport

        if (duplicateDetector.isDuplicate(transaction.hash)) {
            return
        }

        if (unknownHashCache.putIfAbsent(block.sendHash, block.sendHash) != null) {
            return
        }

        val request = AttoTransactionRequest(block.sendHash)

        val socketAddress = randomUri(votes)
        val message =
            if (socketAddress != null) {
                DirectNetworkMessage(socketAddress, request)
            } else {
                BroadcastNetworkMessage(BroadcastStrategy.EVERYONE, setOf(), request)
            }

        networkMessagePublisher.publish(message)
    }

    @EventListener
    fun process(message: InboundNetworkMessage<AttoTransactionResponse>) {
        val response = message.payload
        val transaction = response.transaction

        if (unknownHashCache.remove(transaction.hash) == null) {
            return
        }

        logger.debug { "Discovered missing send transaction ${transaction.hash}" }

        eventPublisher.publish(TransactionDiscovered(null, transaction.toTransaction(), listOf()))
    }

    private fun randomUri(votes: Collection<Vote>): URI? =
        votes
            .asSequence()
            .map { v -> peers[v.publicKey] }
            .filterNotNull()
            .firstOrNull()

    override fun clear() {
        peers.clear()
        unknownHashCache.clear()
    }
}
