package org.atto.node.bootstrap.unchecked

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.commons.ReceiveSupportBlock
import org.atto.node.bootstrap.discovery.DependencyProcessor
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
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

@Component
class SendNotFoundFoundDependencyProcessor(
    val uncheckedTransactionService: UncheckedTransactionService,
    private val networkMessagePublisher: NetworkMessagePublisher,
) : DependencyProcessor {
    private val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName("SendNotFoundFoundDependencyProcessor"))

    private val peers = ConcurrentHashMap<AttoPublicKey, InetSocketAddress>()

    private val unknownHash = ConcurrentHashMap.newKeySet<AttoHash>()

    override fun type() = TransactionRejectionReason.SEND_NOT_FOUND

    @EventListener
    fun add(peerEvent: PeerAdded) {
        peers[peerEvent.peer.node.publicKey] = peerEvent.peer.connectionSocketAddress
    }

    @EventListener
    fun remove(peerEvent: PeerRemoved) {
        peers.remove(peerEvent.peer.node.publicKey)
    }

    override fun process(transaction: Transaction, votes: List<Vote>) {
        val block = transaction.block as ReceiveSupportBlock

        unknownHash.add(block.sendHash)

        val socketAddress = randomSocketAddress(votes)
        val request = AttoTransactionRequest(block.sendHash)

        val message = if (socketAddress != null) {
            OutboundNetworkMessage(socketAddress, request)
        } else {
            BroadcastNetworkMessage(BroadcastStrategy.VOTERS, setOf(), request)
        }

        networkMessagePublisher.publish(message)

        ioScope.launch {
            uncheckedTransactionService.save(transaction.toUncheckedTransaction())
        }
    }

    @EventListener
    fun process(message: InboundNetworkMessage<AttoTransactionResponse>) {
        val response = message.payload
        val transaction = response.transaction

        if (!unknownHash.remove(transaction.hash)) {
            return
        }

        ioScope.launch {
            uncheckedTransactionService.save(transaction.toTransaction().toUncheckedTransaction())
        }

    }

    private fun randomSocketAddress(votes: List<Vote>): InetSocketAddress? {
        return votes.asSequence()
            .map { v -> peers[v.publicKey] }
            .filterNotNull()
            .firstOrNull()
    }

}