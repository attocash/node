package atto.node.transaction

import atto.node.network.InboundNetworkMessage
import atto.node.network.NetworkMessagePublisher
import atto.node.network.OutboundNetworkMessage
import atto.protocol.transaction.AttoTransactionRequest
import atto.protocol.transaction.AttoTransactionResponse
import atto.protocol.transaction.AttoTransactionStreamRequest
import atto.protocol.transaction.AttoTransactionStreamResponse
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.asFlux
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class TransactionNetworkProvider(
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher
) {
    val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName(this.javaClass.simpleName))

    @PreDestroy
    fun preDestroy() {
        ioScope.cancel()
    }
    @EventListener
    fun find(message: InboundNetworkMessage<AttoTransactionRequest>) {
        ioScope.launch {
            val request = message.payload
            val transaction = transactionRepository.findById(request.hash)
            if (transaction != null) {
                val response = AttoTransactionResponse(transaction.toAttoTransaction())
                networkMessagePublisher.publish(OutboundNetworkMessage(message.socketAddress, response))
            }
        }
    }


    @EventListener
    fun stream(message: InboundNetworkMessage<AttoTransactionStreamRequest>) {
        ioScope.launch {
            val request = message.payload
            val transactions = transactionRepository.findDesc(
                request.publicKey,
                request.startHeight,
                request.endHeight - 1U
            )
            transactions.asFlux(this.coroutineContext) // https://github.com/Kotlin/kotlinx.coroutines/issues/1290
                .map { it.toAttoTransaction() }
                .buffer(50)
                .asFlow()
                .collect {
                    val response = AttoTransactionStreamResponse(it)
                    networkMessagePublisher.publish(OutboundNetworkMessage(message.socketAddress, response))
                }
        }
    }
}