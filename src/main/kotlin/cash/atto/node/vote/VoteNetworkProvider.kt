package cash.atto.node.vote

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoSignedVote
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoVote
import cash.atto.commons.toAttoVersion
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.TransactionRepository
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoVoteStreamCancel
import cash.atto.protocol.AttoVoteStreamRequest
import cash.atto.protocol.AttoVoteStreamResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val signer: AttoSigner,
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

        val onSpotVote = if (thisNode.isVoter() && transactionRepository.existsById(request.blockHash)) {
            val attoVote =
                AttoVote(
                    version = 0U.toAttoVersion(),
                    algorithm = thisNode.algorithm,
                    publicKey = thisNode.publicKey,
                    blockAlgorithm = AttoAlgorithm.V1,
                    blockHash = request.blockHash,
                    timestamp = AttoVote.finalTimestamp,
                )
            val attoSignedVote =
                AttoSignedVote(
                    vote = attoVote,
                    signature = signer.sign(attoVote),
                )
            listOf(attoSignedVote)
        } else {
            listOf()
        }

        val votes = voteRepository.findByBlockHash(request.blockHash).map { it.toAtto() }
        merge(votes, onSpotVote.asFlow())
            .takeWhile { voteStreams.contains(stream) }
            .collect {
                val response = AttoVoteStreamResponse(it)
                networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
                delay(10.milliseconds)
            }
    }

    private data class VoteStream(
        val publicUri: URI,
        val blockHash: AttoHash,
    )
}
