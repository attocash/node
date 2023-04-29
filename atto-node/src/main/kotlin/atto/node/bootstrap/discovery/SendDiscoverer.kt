package atto.node.bootstrap.discovery

import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import atto.commons.AttoHash
import atto.commons.AttoPublicKey
import atto.commons.ReceiveSupportBlock
import atto.node.CacheSupport
import atto.node.EventPublisher
import atto.node.bootstrap.TransactionDiscovered
import atto.node.bootstrap.TransactionStuck
import atto.node.network.*
import atto.node.network.peer.PeerAdded
import atto.node.network.peer.PeerRemoved
import atto.node.transaction.Transaction
import atto.node.transaction.TransactionRejectionReason
import atto.node.transaction.toTransaction
import atto.node.vote.Vote
import atto.protocol.transaction.AttoTransactionRequest
import atto.protocol.transaction.AttoTransactionResponse
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Component
class SendDiscoverer(
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val eventPublisher: EventPublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val peers = ConcurrentHashMap<AttoPublicKey, InetSocketAddress>()

    private val unknownHashCache = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build<AttoHash, AttoHash>()
        .asMap()

    @EventListener
    @Async
    fun add(peerEvent: PeerAdded) {
        peers[peerEvent.peer.node.publicKey] = peerEvent.peer.connectionSocketAddress
    }

    @EventListener
    @Async
    fun remove(peerEvent: PeerRemoved) {
        peers.remove(peerEvent.peer.node.publicKey)
    }

    @EventListener
    @Async
    fun process(event: TransactionDiscovered) {
        process(event.reason, event.transaction, event.votes)
    }

    @EventListener
    @Async
    fun process(event: TransactionStuck) {
        process(event.reason, event.transaction, listOf())
    }

    private fun process(reason: TransactionRejectionReason?, transaction: Transaction, votes: Collection<Vote>) {
        if (reason != TransactionRejectionReason.RECEIVABLE_NOT_FOUND) {
            return
        }

        val block = transaction.block as ReceiveSupportBlock

        if (unknownHashCache.putIfAbsent(block.sendHash, block.sendHash) != null) {
            return
        }

        val request = AttoTransactionRequest(block.sendHash)

        val socketAddress = randomSocketAddress(votes)
        val message = if (socketAddress != null) {
            OutboundNetworkMessage(socketAddress, request)
        } else {
            BroadcastNetworkMessage(BroadcastStrategy.VOTERS, setOf(), request)
        }

        networkMessagePublisher.publish(message)
    }

    @EventListener
    @Async
    fun process(message: InboundNetworkMessage<AttoTransactionResponse>) {
        val response = message.payload
        val transaction = response.transaction

        if (unknownHashCache.remove(transaction.hash) == null) {
            return
        }

        eventPublisher.publish(TransactionDiscovered(null, transaction.toTransaction(), listOf()))
    }

    private fun randomSocketAddress(votes: Collection<Vote>): InetSocketAddress? {
        return votes.asSequence()
            .map { v -> peers[v.publicKey] }
            .filterNotNull()
            .firstOrNull()
    }

    override fun clear() {
        peers.clear()
        unknownHashCache.clear()
    }

}