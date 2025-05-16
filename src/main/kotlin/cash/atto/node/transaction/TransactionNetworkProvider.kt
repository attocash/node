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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

@Component
class TransactionNetworkProvider(
    private val thisNode: AttoNode,
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
) {
    private val peers = ConcurrentHashMap.newKeySet<URI>()
    private val mutex = Mutex()

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

        mutex.withLock {
            if (!peers.contains(message.publicUri)) {
                return
            }

            val request = message.payload
            val transaction = transactionRepository.findById(request.hash)
            if (transaction != null) {
                val response = AttoTransactionResponse(transaction.toAttoTransaction())
                networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
            }
        }
    }

    @EventListener
    suspend fun stream(message: InboundNetworkMessage<AttoTransactionStreamRequest>) {
        if (thisNode.isNotHistorical()) {
            return
        }

        mutex.withLock {
            if (!peers.contains(message.publicUri)) {
                return
            }

            val request = message.payload
            val transactions =
                transactionRepository.findDesc(
                    request.publicKey,
                    request.startHeight,
                    request.endHeight,
                )

            transactions
                .takeWhile { peers.contains(message.publicUri) }
                .collect {
                    val response = AttoTransactionStreamResponse(it.toAttoTransaction())
                    networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
                    delay(10.milliseconds)
                }
        }
    }
}
