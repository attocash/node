package org.atto.node.transaction

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.network.OutboundNetworkMessage
import org.atto.protocol.transaction.AttoTransactionRequest
import org.atto.protocol.transaction.AttoTransactionResponse
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class TransactionFinder(
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher
) {
    val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName("TransactionFinder"))

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
}