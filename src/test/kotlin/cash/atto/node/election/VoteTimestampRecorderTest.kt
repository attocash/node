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
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoVersion
import cash.atto.node.transaction.Transaction
import cash.atto.node.vote.Vote
import cash.atto.node.vote.weight.WeightService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class VoteTimestampRecorderTest {
    @Test
    fun `should record latest vote timestamps`() =
        runTest {
            // Given
            val weightService = mockk<WeightService>()
            val recorder = VoteTimestampRecorder(weightService)
            val transaction = Transaction.sample()
            val firstPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
            val secondPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
            val oldTimestamp = Instant.now().minusSeconds(10)
            val newTimestamp = Instant.now()
            val secondTimestamp = Instant.now().plusSeconds(1)
            val timestampUpdates = CopyOnWriteArrayList<Map<AttoPublicKey, Instant>>()

            coEvery { weightService.recordLastVoteTimestamps(any()) } coAnswers {
                timestampUpdates += firstArg<Map<AttoPublicKey, Instant>>()
            }

            try {
                // When
                recorder.process(
                    ElectionConsensusReached(
                        account = mockk(relaxed = true),
                        transaction = transaction,
                        votes =
                            listOf(
                                Vote.sample(transaction.hash, firstPublicKey, oldTimestamp),
                                Vote.sample(transaction.hash, secondPublicKey, secondTimestamp),
                                Vote.sample(transaction.hash, firstPublicKey, newTimestamp),
                            ),
                    ),
                )

                // Then
                awaitCondition { recorder.getPendingSize() == 0 }
                assertEquals(
                    mapOf(
                        firstPublicKey to newTimestamp,
                        secondPublicKey to secondTimestamp,
                    ),
                    timestampUpdates.single(),
                )
            } finally {
                recorder.stop()
            }
        }

    @Test
    fun `worker retries pending timestamps without another delay`() =
        runTest {
            // Given
            val weightService = mockk<WeightService>()
            val recorder = VoteTimestampRecorder(weightService)
            val transaction = Transaction.sample()
            val vote = Vote.sample(transaction.hash, AttoPublicKey(Random.nextBytes(ByteArray(32))), Instant.now())
            val attempts = AtomicInteger()

            coEvery { weightService.recordLastVoteTimestamps(any()) } coAnswers {
                if (attempts.incrementAndGet() == 1) {
                    throw IllegalStateException("db down")
                }
            }

            try {
                // When
                recorder.process(ElectionConsensusReached(mockk(relaxed = true), transaction, listOf(vote)))

                // Then
                awaitCondition { recorder.getPendingSize() == 0 && attempts.get() == 2 }
            } finally {
                recorder.stop()
            }
        }

    @Test
    fun `worker records a newer timestamp that arrives during flush without another delay`() =
        runTest {
            // Given
            val weightService = mockk<WeightService>()
            val recorder = VoteTimestampRecorder(weightService)
            val transaction = Transaction.sample()
            val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
            val oldTimestamp = Instant.now().minusSeconds(10)
            val newTimestamp = Instant.now()
            val timestampUpdates = CopyOnWriteArrayList<Map<AttoPublicKey, Instant>>()

            coEvery { weightService.recordLastVoteTimestamps(any()) } coAnswers {
                timestampUpdates += firstArg<Map<AttoPublicKey, Instant>>()
                if (timestampUpdates.size == 1) {
                    recorder.process(
                        ElectionConsensusReached(
                            account = mockk(relaxed = true),
                            transaction = transaction,
                            votes = listOf(Vote.sample(transaction.hash, publicKey, newTimestamp)),
                        ),
                    )
                }
            }

            try {
                // When
                recorder.process(
                    ElectionConsensusReached(
                        account = mockk(relaxed = true),
                        transaction = transaction,
                        votes = listOf(Vote.sample(transaction.hash, publicKey, oldTimestamp)),
                    ),
                )

                // Then
                awaitCondition { recorder.getPendingSize() == 0 && timestampUpdates.size == 2 }
                assertEquals(listOf(mapOf(publicKey to oldTimestamp), mapOf(publicKey to newTimestamp)), timestampUpdates)
            } finally {
                recorder.stop()
            }
        }

    private fun awaitCondition(condition: () -> Boolean) {
        await()
            .atMost(5, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.MILLISECONDS)
            .until(condition)
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
        publicKey: AttoPublicKey,
        receivedAt: Instant,
    ): Vote =
        Vote(
            hash = AttoHash(Random.nextBytes(ByteArray(32))),
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = publicKey,
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = blockHash,
            timestamp = Instant.now(),
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            weight = AttoAmount(1UL),
            receivedAt = receivedAt,
        )
}
