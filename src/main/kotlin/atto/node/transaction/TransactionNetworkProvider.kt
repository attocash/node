package atto.node.transaction

import atto.node.network.DirectNetworkMessage
import atto.node.network.InboundNetworkMessage
import atto.node.network.NetworkMessagePublisher
import atto.protocol.transaction.AttoTransactionRequest
import atto.protocol.transaction.AttoTransactionResponse
import atto.protocol.transaction.AttoTransactionStreamRequest
import atto.protocol.transaction.AttoTransactionStreamResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.asFlux
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

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
            networkMessagePublisher.publish(DirectNetworkMessage(message.socketAddress, response))
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

        // no support to chunked https://github.com/Kotlin/kotlinx.coroutines/issues/1290
        transactions.asFlux(Dispatchers.Default)
            .map { it.toAttoTransaction() }
            .buffer(50)
            .asFlow()
            .collect {
                val response = AttoTransactionStreamResponse(it)
                networkMessagePublisher.publish(DirectNetworkMessage(message.socketAddress, response))
            }
    }
}