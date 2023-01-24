package org.atto.node.vote

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.network.OutboundNetworkMessage
import org.atto.protocol.vote.AttoVoteRequest
import org.atto.protocol.vote.AttoVoteResponse
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class VoteNetworkProvider(
    private val voteRepository: VoteRepository,
    private val networkMessagePublisher: NetworkMessagePublisher
) {
    val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName(this.javaClass.simpleName))

    @EventListener
    @Async
    fun process(message: InboundNetworkMessage<AttoVoteRequest>) {
        ioScope.launch {
            val request = message.payload
            val votes = voteRepository.findByHash(request.hash, AttoVoteResponse.maxCount)
            if (!votes.isEmpty()) {
                val response = AttoVoteResponse(votes.map { it.toAttoVote() })
                networkMessagePublisher.publish(OutboundNetworkMessage(message.socketAddress, response))
            }
        }
    }
}