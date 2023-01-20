package org.atto.node.bootstrap.discovery

import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.commons.ReceiveSupportBlock
import org.atto.node.EventPublisher
import org.atto.node.bootstrap.TransactionDiscovered
import org.atto.node.bootstrap.TransactionStuck
import org.atto.node.network.*
import org.atto.node.network.peer.PeerAdded
import org.atto.node.network.peer.PeerRemoved
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.transaction.toTransaction
import org.atto.node.vote.Vote
import org.atto.protocol.transaction.AttoTransactionRequest
import org.atto.protocol.transaction.AttoTransactionResponse
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

@Component
class SendDiscoverer(
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val eventPublisher: EventPublisher,
) {
    private val peers = ConcurrentHashMap<AttoPublicKey, InetSocketAddress>()

    private val unknownHash = ConcurrentHashMap.newKeySet<AttoHash>()

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

        if (!unknownHash.add(block.sendHash)) {
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

        if (!unknownHash.remove(transaction.hash)) {
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

}