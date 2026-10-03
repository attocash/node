package cash.atto.node.vote

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.toAttoVersion
import io.mockk.coEvery
import io.mockk.coVerify
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

internal class VoteServiceTest {
    @Test
    fun `worker waits once then drains every queued batch`() =
        runTest {
            // Given
            val repository = mockk<VoteRepository>()
            val service = VoteService(repository)
            val votes = List(1_001) { Vote.sample() }
            val savedVotes = CopyOnWriteArrayList<List<Vote>>()
            coEvery { repository.insertIgnoreAll(any()) } coAnswers {
                savedVotes += firstArg<Collection<Vote>>().toList()
                firstArg<Collection<Vote>>().size.toLong()
            }

            try {
                // When
                service.enqueueAll(votes)

                // Then
                awaitCondition { service.getBufferSize() == 0 }
                assertEquals(listOf(votes.take(1_000), votes.drop(1_000)), savedVotes)
            } finally {
                service.stop()
            }
        }

    @Test
    fun `worker remains available after a failed insert`() =
        runTest {
            // Given
            val repository = mockk<VoteRepository>()
            val service = VoteService(repository)
            val failedVote = Vote.sample()
            val savedVote = Vote.sample()
            val attempts = AtomicInteger()
            coEvery { repository.insertIgnoreAll(any()) } coAnswers {
                if (attempts.incrementAndGet() == 1) {
                    throw IllegalStateException("db down")
                }
                1
            }

            try {
                // When
                service.enqueue(failedVote)

                // Then
                awaitCondition { attempts.get() == 1 && service.getBufferSize() == 0 }

                // When
                service.enqueue(savedVote)

                // Then
                awaitCondition { attempts.get() == 2 && service.getBufferSize() == 0 }
                coVerify(exactly = 1) { repository.insertIgnoreAll(listOf(failedVote)) }
                coVerify(exactly = 1) { repository.insertIgnoreAll(listOf(savedVote)) }
            } finally {
                service.stop()
            }
        }

    private fun awaitCondition(condition: () -> Boolean) {
        await()
            .atMost(5, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.MILLISECONDS)
            .until(condition)
    }

    private fun Vote.Companion.sample(): Vote =
        Vote(
            hash = AttoHash(Random.nextBytes(ByteArray(32))),
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = AttoHash(Random.nextBytes(ByteArray(32))),
            timestamp = Instant.now(),
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            weight = AttoAmount(1UL),
        )
}
