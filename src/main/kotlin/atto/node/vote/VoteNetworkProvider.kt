package atto.node.vote

import atto.node.network.InboundNetworkMessage
import atto.node.network.NetworkMessagePublisher
import atto.node.network.OutboundNetworkMessage
import atto.protocol.vote.AttoVoteRequest
import atto.protocol.vote.AttoVoteResponse
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class VoteNetworkProvider(
    private val voteRepository: VoteRepository,
    private val networkMessagePublisher: NetworkMessagePublisher
) {
    val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName(this.javaClass.simpleName))

    @PreDestroy
    fun preDestroy() {
        ioScope.cancel()
    }

    @EventListener
    fun process(message: InboundNetworkMessage<AttoVoteRequest>) {
        ioScope.launch {
            val request = message.payload
            val votes = voteRepository.findByHash(request.hash, AttoVoteResponse.maxCount)
            if (votes.isNotEmpty()) {
                val response = AttoVoteResponse(votes.map { it.toAttoVote() })
                networkMessagePublisher.publish(OutboundNetworkMessage(message.socketAddress, response))
            }
        }
    }
}