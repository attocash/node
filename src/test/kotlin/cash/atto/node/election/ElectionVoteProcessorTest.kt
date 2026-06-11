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
import cash.atto.node.vote.weight.WeightService
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Instant
import kotlin.random.Random

class ElectionVoteProcessorTest {
    @Test
    fun `should save final votes and update latest vote timestamps for historical node`() =
        runBlocking {
            val transactionManager = RecordingReactiveTransactionManager()
            val voteService = mockk<VoteService>()
            val weightService = mockk<WeightService>()
            val processor = newProcessor(historical = true, voteService, weightService, transactionManager)
            val transaction = Transaction.sample()
            val finalVote = Vote.sample(transaction.hash, final = true)
            val provisionalVote = Vote.sample(transaction.hash, final = false)
            val savedVotes = mutableListOf<List<Vote>>()
            val timestampUpdates = mutableListOf<Map<AttoPublicKey, Instant>>()

            coEvery { voteService.saveAll(any()) } coAnswers {
                savedVotes += firstArg<Collection<Vote>>().toList()
                emptyList()
            }
            coEvery { weightService.updateLastVoteTimestamps(any()) } coAnswers {
                timestampUpdates += firstArg<Map<AttoPublicKey, Instant>>()
            }

            processor.process(ElectionConsensusReached(mockk(relaxed = true), transaction, listOf(finalVote, provisionalVote)))
            processor.flush()

            assertEquals(0, processor.getBufferSize())
            assertEquals(listOf(listOf(finalVote)), savedVotes)
            assertEquals(
                mapOf(
                    finalVote.publicKey to finalVote.receivedAt,
                    provisionalVote.publicKey to provisionalVote.receivedAt,
                ),
                timestampUpdates.single(),
            )
            assertEquals(1, transactionManager.commits)
            assertEquals(0, transactionManager.rollbacks)
        }

    @Test
    fun `should not save votes for non historical node`() =
        runBlocking {
            val transactionManager = RecordingReactiveTransactionManager()
            val voteService = mockk<VoteService>(relaxed = true)
            val weightService = mockk<WeightService>()
            val processor = newProcessor(historical = false, voteService, weightService, transactionManager)
            val transaction = Transaction.sample()
            val finalVote = Vote.sample(transaction.hash, final = true)

            coEvery { weightService.updateLastVoteTimestamps(any()) } returns Unit

            processor.process(ElectionConsensusReached(mockk(relaxed = true), transaction, listOf(finalVote)))
            processor.flush()

            assertEquals(0, processor.getBufferSize())
            coVerify(exactly = 0) { voteService.saveAll(any()) }
            coVerify(exactly = 1) { weightService.updateLastVoteTimestamps(mapOf(finalVote.publicKey to finalVote.receivedAt)) }
        }

    @Test
    fun `should requeue events when vote persistence fails`() =
        runBlocking {
            val transactionManager = RecordingReactiveTransactionManager()
            val voteService = mockk<VoteService>()
            val weightService = mockk<WeightService>()
            val processor = newProcessor(historical = true, voteService, weightService, transactionManager)
            val transaction = Transaction.sample()
            val finalVote = Vote.sample(transaction.hash, final = true)
            var attempts = 0

            coEvery { weightService.updateLastVoteTimestamps(any()) } returns Unit
            coEvery { voteService.saveAll(any()) } coAnswers {
                attempts++
                if (attempts == 1) {
                    throw IllegalStateException("db down")
                }
                emptyList()
            }

            processor.process(ElectionConsensusReached(mockk(relaxed = true), transaction, listOf(finalVote)))

            assertThrows<RuntimeException> {
                runBlocking {
                    processor.flush()
                }
            }

            assertEquals(1, processor.getBufferSize())
            assertEquals(1, attempts)
            assertEquals(0, transactionManager.commits)
            assertEquals(1, transactionManager.rollbacks)

            processor.flush()

            assertEquals(0, processor.getBufferSize())
            assertEquals(2, attempts)
            assertEquals(1, transactionManager.commits)
            assertEquals(1, transactionManager.rollbacks)
        }

    private fun newProcessor(
        historical: Boolean,
        voteService: VoteService,
        weightService: WeightService,
        transactionManager: ReactiveTransactionManager,
    ): ElectionVoteProcessor =
        ElectionVoteProcessor(
            thisNode = sampleNode(historical),
            voteService = voteService,
            weightService = weightService,
            transactionManager = transactionManager,
        )

    private class RecordingReactiveTransactionManager : ReactiveTransactionManager {
        var commits = 0
            private set
        var rollbacks = 0
            private set

        override fun getReactiveTransaction(definition: TransactionDefinition?): Mono<ReactiveTransaction> =
            Mono.just(SimpleReactiveTransaction)

        override fun commit(transaction: ReactiveTransaction): Mono<Void> = Mono.fromRunnable<Void> { commits++ }

        override fun rollback(transaction: ReactiveTransaction): Mono<Void> = Mono.fromRunnable<Void> { rollbacks++ }
    }

    private object SimpleReactiveTransaction : ReactiveTransaction

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
