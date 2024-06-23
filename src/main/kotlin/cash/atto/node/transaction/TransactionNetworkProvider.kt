package cash.atto.node.transaction

import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionRequest
import cash.atto.protocol.AttoTransactionResponse
import cash.atto.protocol.AttoTransactionStreamRequest
import cash.atto.protocol.AttoTransactionStreamResponse
import kotlinx.coroutines.delay
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.milliseconds

@Component
class TransactionNetworkProvider(
    private val thisNode: AttoNode,
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
) {
    @EventListener
    suspend fun find(message: InboundNetworkMessage<AttoTransactionRequest>) {
        if (thisNode.isNotHistorical()) {
            return
        }
        val request = message.payload
        val transaction = transactionRepository.findById(request.hash)
        if (transaction != null) {
            val response = AttoTransactionResponse(transaction.toAttoTransaction())
            networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
        }
    }

    @EventListener
    suspend fun stream(message: InboundNetworkMessage<AttoTransactionStreamRequest>) {
        if (thisNode.isNotHistorical()) {
            return
        }
        val request = message.payload
        val transactions =
            transactionRepository.findDesc(
                request.publicKey,
                request.startHeight,
                request.endHeight - 1U,
            )

        transactions.collect {
            val response = AttoTransactionStreamResponse(it.toAttoTransaction())
            networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
            delay(10.milliseconds)
        }
    }
}
