package cash.atto.node.vote

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoSignedVote
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoVote
import cash.atto.commons.toAttoVersion
import cash.atto.node.CacheSupport
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.TransactionRepository
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoVoteRequest
import cash.atto.protocol.AttoVoteResponse
import cash.atto.protocol.AttoVoteStreamCancel
import cash.atto.protocol.AttoVoteStreamRequest
import cash.atto.protocol.AttoVoteStreamResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.time.Duration.Companion.milliseconds

@Component
class VoteNetworkProvider(
    private val thisNode: AttoNode,
    private val voteRepository: VoteRepository,
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val signer: AttoSigner,
) : CacheSupport {
    companion object {
        private const val MAX_CONCURRENT_GETS = 64
        private const val MAX_CONCURRENT_STREAMS = 64
    }

    private val voteStreams = ConcurrentHashMap.newKeySet<VoteStream>()
    private val getPermits = Semaphore(MAX_CONCURRENT_GETS)
    private val streamPermits = Semaphore(MAX_CONCURRENT_STREAMS)

    override fun clear() {
        voteStreams.clear()
    }

    @EventListener
    suspend fun get(message: InboundNetworkMessage<AttoVoteRequest>) {
        val request = message.payload

        if (!thisNode.isVoter()) {
            return
        }

        if (!getPermits.tryAcquire()) {
            return
        }

        try {
            if (!transactionRepository.existsById(request.blockHash)) {
                return
            }

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

            val response = AttoVoteResponse(attoSignedVote)

            networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
        } finally {
            getPermits.release()
        }
    }

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

        if (!streamPermits.tryAcquire()) {
            voteStreams.remove(stream)
            return
        }

        try {
            val votes = voteRepository.findByBlockHash(request.blockHash).map { it.toAtto() }

            votes
                .takeWhile { voteStreams.contains(stream) }
                .collect {
                    val response = AttoVoteStreamResponse(it)
                    networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
                    delay(10.milliseconds)
                }
        } finally {
            voteStreams.remove(stream)
            streamPermits.release()
        }
    }

    private data class VoteStream(
        val publicUri: URI,
        val blockHash: AttoHash,
    )
}
