package cash.atto.node.vote.keeping

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoSignedVote
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoVote
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toBigInteger
import cash.atto.node.CacheSupport
import cash.atto.node.network.ConnectedPeers
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.vote.MissingVote
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteRepository
import cash.atto.node.vote.VoteService
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoVoteRequest
import cash.atto.protocol.AttoVoteResponse
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
class VoteKeeper(
    private val thisNode: AttoNode,
    private val voteWeighter: VoteWeighter,
    private val voteRepository: VoteRepository,
    private val voteService: VoteService,
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val signer: AttoSigner,
    private val connectedPeers: ConnectedPeers,
) : CacheSupport {
    companion object {
        internal val cacheExpiration = Duration.ofSeconds(10)
    }

    private val missingVoteMap =
        Caffeine
            .newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(cacheExpiration)
            .build<MissingVote, MissingVote>()
            .asMap()

    @EventListener
    suspend fun process(message: InboundNetworkMessage<AttoVoteResponse>) {
        val response = message.payload
        val signedVote = response.vote
        val missingVote = MissingVote(signedVote.vote.blockHash, signedVote.vote.publicKey)

        if (missingVoteMap.remove(missingVote) == null) {
            return
        }

        voteService.enqueue(Vote.from(voteWeighter.get(signedVote.vote.publicKey), signedVote))
    }

    @Scheduled(fixedRate = 10, timeUnit = TimeUnit.MINUTES)
    suspend fun keep() {
        if (!thisNode.isHistorical()) {
            return
        }

        val minimalWeight = voteWeighter.getMinimalConfirmationWeight()
        val missingVote = voteRepository.findMissingVote(minimalWeight.raw.toBigInteger())

        missingVote.forEach {
            keep(it)
        }
    }

    private suspend fun keep(missingVote: MissingVote) {
        if (missingVote.representativePublicKey == thisNode.publicKey) {
            val signedVote = getLocalVote(missingVote.lastTransactionHash)
            voteService.enqueue(signedVote)
            return
        }

        val representative =
            connectedPeers
                .snapshot()
                .firstOrNull {
                    it.node.isHistorical() && it.node.publicKey == missingVote.representativePublicKey
                }?.node
                ?.publicUri ?: return
        missingVoteMap.computeIfAbsent(missingVote) {
            val voteRequest = AttoVoteRequest(AttoAlgorithm.V1, missingVote.lastTransactionHash)
            val message = DirectNetworkMessage(representative, voteRequest)
            networkMessagePublisher.publish(message)
            missingVote
        }
    }

    private suspend fun getLocalVote(blockHash: AttoHash): Vote {
        val attoVote =
            AttoVote(
                version = 0U.toAttoVersion(),
                algorithm = thisNode.algorithm,
                publicKey = thisNode.publicKey,
                blockAlgorithm = AttoAlgorithm.V1,
                blockHash = blockHash,
                timestamp = AttoVote.finalTimestamp,
            )
        val signedVote =
            AttoSignedVote(
                vote = attoVote,
                signature = signer.sign(attoVote),
            )

        return Vote.from(voteWeighter.get(signedVote.vote.publicKey), signedVote)
    }

    override fun clear() {
        missingVoteMap.clear()
    }
}
