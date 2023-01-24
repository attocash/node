package org.atto.node.transaction

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.asFlux
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.network.OutboundNetworkMessage
import org.atto.protocol.transaction.AttoTransactionRequest
import org.atto.protocol.transaction.AttoTransactionResponse
import org.atto.protocol.transaction.AttoTransactionStreamRequest
import org.atto.protocol.transaction.AttoTransactionStreamResponse
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class TransactionNetworkProvider(
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher
) {
    val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName(this.javaClass.simpleName))

    @EventListener
    @Async
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
    @Async
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