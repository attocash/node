package cash.atto.node.election

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoVote
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.node.transaction.Transaction
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteService
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import kotlin.random.Random

class FinalVoteRecorderTest {
    @Test
    fun `should enqueue final votes for historical node`() =
        run {
            // given
            val voteService = mockk<VoteService>()
            val recorder = newRecorder(historical = true, voteService)
            val transaction = Transaction.sample()
            val finalVote = Vote.sample(transaction.hash, final = true)
            val provisionalVote = Vote.sample(transaction.hash, final = false)
            val savedVotes = mutableListOf<List<Vote>>()

            every { voteService.enqueueAll(any()) } answers {
                savedVotes += firstArg<Collection<Vote>>().toList()
            }

            // when
            recorder.process(ElectionConsensusReached(mockk(relaxed = true), transaction, listOf(finalVote, provisionalVote)))

            // then
            assertEquals(listOf(listOf(finalVote)), savedVotes)
        }

    @Test
    fun `should not queue votes for non historical node`() =
        run {
            // given
            val voteService = mockk<VoteService>(relaxed = true)
            val recorder = newRecorder(historical = false, voteService)
            val transaction = Transaction.sample()
            val finalVote = Vote.sample(transaction.hash, final = true)

            // when
            recorder.process(ElectionConsensusReached(mockk(relaxed = true), transaction, listOf(finalVote)))

            // then
            verify(exactly = 0) { voteService.enqueueAll(any()) }
        }

    private fun newRecorder(
        historical: Boolean,
        voteService: VoteService,
    ): FinalVoteRecorder =
        FinalVoteRecorder(
            thisNode = sampleNode(historical),
            voteService = voteService,
        )

    private fun sampleNode(historical: Boolean): AttoNode {
        val features =
            if (historical) {
                setOf(NodeFeature.VOTING, NodeFeature.HISTORICAL)
            } else {
                setOf(NodeFeature.VOTING)
            }
        return AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0U.toUShort(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            publicUri = URI("ws://127.0.0.1:8081"),
            features = features,
        )
    }

    private fun Transaction.Companion.sample(): Transaction =
        Transaction(
            AttoReceiveBlock(
                version = 0U.toAttoVersion(),
                network = AttoNetwork.LOCAL,
                algorithm = AttoAlgorithm.V1,
                publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
                height = AttoHeight(2UL),
                balance = AttoAmount.MAX,
                timestamp = AttoInstant.now(),
                previous = AttoHash(Random.nextBytes(ByteArray(32))),
                sendHashAlgorithm = AttoAlgorithm.V1,
                sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
            ),
            AttoSignature(Random.nextBytes(ByteArray(64))),
            AttoWork(Random.nextBytes(ByteArray(8))),
        )

    private fun Vote.Companion.sample(
        blockHash: AttoHash,
        final: Boolean,
        publicKey: AttoPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
        receivedAt: Instant = Instant.now(),
    ): Vote =
        Vote(
            hash = AttoHash(Random.nextBytes(ByteArray(32))),
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = publicKey,
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = blockHash,
            timestamp = if (final) AttoVote.finalTimestamp.toJavaInstant() else Instant.now(),
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            weight = AttoAmount(1UL),
            receivedAt = receivedAt,
        )
}
