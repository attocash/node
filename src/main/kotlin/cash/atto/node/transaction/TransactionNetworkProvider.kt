package cash.atto.node.transaction

import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionRequest
import cash.atto.protocol.AttoTransactionResponse
import cash.atto.protocol.AttoTransactionStreamRequest
import cash.atto.protocol.AttoTransactionStreamResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

@Component
class TransactionNetworkProvider(
    private val thisNode: AttoNode,
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val clock: Clock,
) {
    companion object {
        private const val MAX_CONCURRENT_FINDS = 64
    }

    private val peers = ConcurrentHashMap.newKeySet<URI>()
    private val findPermits = Semaphore(MAX_CONCURRENT_FINDS)
    private val streamMutex = Mutex()

    @EventListener
    fun add(nodeEvent: NodeConnected) {
        val node = nodeEvent.node
        peers.add(node.publicUri)
    }

    @EventListener
    fun remove(nodeEvent: NodeDisconnected) {
        val node = nodeEvent.node
        peers.remove(node.publicUri)
    }

    @EventListener
    suspend fun find(message: InboundNetworkMessage<AttoTransactionRequest>) {
        if (thisNode.isNotHistorical()) {
            return
        }

        if (!peers.contains(message.publicUri)) {
            return
        }

        if (!findPermits.tryAcquire()) {
            return
        }

        try {
            val request = message.payload
            val transaction = transactionRepository.findById(request.hash)
            if (transaction != null) {
                val response = AttoTransactionResponse(transaction.toAttoTransaction())
                networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
            }
        } finally {
            findPermits.release()
        }
    }

    @EventListener
    suspend fun stream(message: InboundNetworkMessage<AttoTransactionStreamRequest>) {
        if (thisNode.isNotHistorical()) {
            return
        }

        if (!streamMutex.tryLock()) {
            return
        }

        try {
            if (!peers.contains(message.publicUri) || message.isExpired()) {
                return
            }

            val request = message.payload
            val transactions =
                transactionRepository
                    .findDesc(
                        request.publicKey,
                        request.startHeight,
                        request.endHeight,
                    ).toList()

            if (message.isExpired()) {
                return
            }

            for (transaction in transactions) {
                if (!peers.contains(message.publicUri)) {
                    break
                }
                val response = AttoTransactionStreamResponse(transaction.toAttoTransaction())
                networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
            }
        } finally {
            streamMutex.unlock()
        }
    }

    private fun InboundNetworkMessage<AttoTransactionStreamRequest>.isExpired(): Boolean =
        timestamp.plus(AttoTransactionStreamRequest.TIMEOUT) <= clock.instant()
}
