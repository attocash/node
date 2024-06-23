package cash.atto.node.vote

import cash.atto.commons.AttoHash
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoVoteStreamCancel
import cash.atto.protocol.AttoVoteStreamRequest
import cash.atto.protocol.AttoVoteStreamResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

@Component
class VoteNetworkProvider(
    private val thisNode: AttoNode,
    private val voteRepository: VoteRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
) {
    private val voteStreams = ConcurrentHashMap.newKeySet<VoteStream>()

    @EventListener
    suspend fun cancel(message: InboundNetworkMessage<AttoVoteStreamCancel>) {
        val request = message.payload
        val stream = VoteStream(message.publicUri, request.blockHash)
        voteStreams.remove(stream)
    }

    @EventListener
    suspend fun stream(message: InboundNetworkMessage<AttoVoteStreamRequest>) {
        if (thisNode.isNotHistorical()) {
            return
        }

        val request = message.payload

        val stream = VoteStream(message.publicUri, request.blockHash)
        if (!voteStreams.add(stream)) {
            return
        }

        val votes = voteRepository.findByHash(request.blockHash)
        votes
            .takeWhile { voteStreams.contains(stream) }
            .collect {
                val response = AttoVoteStreamResponse(request.blockHash, it.toAttoVote())
                networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
                delay(10.milliseconds)
            }
    }

    private data class VoteStream(
        val publicUri: URI,
        val blockHash: AttoHash,
    )
}
