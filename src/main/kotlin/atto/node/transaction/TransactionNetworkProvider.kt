package atto.node.transaction

import atto.node.network.DirectNetworkMessage
import atto.node.network.InboundNetworkMessage
import atto.node.network.NetworkMessagePublisher
import atto.protocol.transaction.AttoTransactionRequest
import atto.protocol.transaction.AttoTransactionResponse
import atto.protocol.transaction.AttoTransactionStreamRequest
import atto.protocol.transaction.AttoTransactionStreamResponse
import kotlinx.coroutines.delay
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.milliseconds

@Component
class TransactionNetworkProvider(
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher
) {
    @EventListener
    suspend fun find(message: InboundNetworkMessage<AttoTransactionRequest>) {
        val request = message.payload
        val transaction = transactionRepository.findById(request.hash)
        if (transaction != null) {
            val response = AttoTransactionResponse(transaction.toAttoTransaction())
            networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
        }
    }


    @EventListener
    suspend fun stream(message: InboundNetworkMessage<AttoTransactionStreamRequest>) {
        val request = message.payload
        val transactions = transactionRepository.findDesc(
            request.publicKey,
            request.startHeight,
            request.endHeight - 1U
        )

        transactions.collect {
            val response = AttoTransactionStreamResponse(it.toAttoTransaction())
            networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
            delay(10.milliseconds)
        }
    }
}