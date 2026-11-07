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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
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
                awaitCondition { savedVotes.sumOf { it.size } == votes.size && service.getBufferSize() == 0 }
                assertEquals(listOf(votes.take(1_000), votes.drop(1_000)), savedVotes)
            } finally {
                service.stop()
            }
        }

    @Test
    fun `worker retries failed votes without another enqueue`() =
        runTest {
            // Given
            val repository = mockk<VoteRepository>()
            val service = VoteService(repository)
            val failedVote = Vote.sample()
            val attempts = AtomicInteger()
            val savedVotes = CopyOnWriteArrayList<Vote>()
            coEvery { repository.insertIgnoreAll(any()) } coAnswers {
                if (attempts.incrementAndGet() == 1) {
                    throw IllegalStateException("db down")
                }
                savedVotes.addAll(firstArg<Collection<Vote>>())
                1
            }

            try {
                // When
                service.enqueue(failedVote)

                // Then
                awaitCondition { savedVotes.size == 1 && service.getBufferSize() == 0 }
                assertEquals(listOf(failedVote), savedVotes)
                coVerify(exactly = 2) { repository.insertIgnoreAll(listOf(failedVote)) }
            } finally {
                service.stop()
            }
        }

    @Test
    fun `failed batch stays ahead of votes arriving during persistence and keeps signature deduplication`() =
        runTest {
            // Given
            val repository = mockk<VoteRepository>()
            val service = VoteService(repository)
            val firstVote = Vote.sample()
            val firstBatch = listOf(firstVote, firstVote) + List(998) { Vote.sample() }
            val laterVote = Vote.sample()
            val firstAttemptStarted = CompletableDeferred<Unit>()
            val failFirstAttempt = CompletableDeferred<Unit>()
            val attemptedBatches = CopyOnWriteArrayList<List<Vote>>()
            val savedBatches = CopyOnWriteArrayList<List<Vote>>()
            coEvery { repository.insertIgnoreAll(any()) } coAnswers {
                val votes = firstArg<Collection<Vote>>().toList()
                attemptedBatches += votes
                if (attemptedBatches.size == 1) {
                    firstAttemptStarted.complete(Unit)
                    failFirstAttempt.await()
                    throw IllegalStateException("db down")
                }
                savedBatches += votes
                votes.size.toLong()
            }

            try {
                // When
                service.enqueueAll(firstBatch)
                awaitCondition { firstAttemptStarted.isCompleted }
                service.enqueue(laterVote)
                failFirstAttempt.complete(Unit)

                // Then
                awaitCondition { savedBatches.size == 2 && service.getBufferSize() == 0 }
                val distinctBatch = firstBatch.distinctBy { it.signature }
                assertEquals(listOf(distinctBatch, distinctBatch, listOf(laterVote)), attemptedBatches)
                assertEquals(listOf(distinctBatch, listOf(laterVote)), savedBatches)
            } finally {
                failFirstAttempt.complete(Unit)
                service.stop()
            }
        }

    @Test
    fun `clear prevents an in flight failure from restoring cleared votes`() =
        runTest {
            // Given
            val repository = mockk<VoteRepository>()
            val service = VoteService(repository)
            val clearedVote = Vote.sample()
            val queuedVote = Vote.sample()
            val newerVote = Vote.sample()
            val firstAttemptStarted = CompletableDeferred<Unit>()
            val failFirstAttempt = CompletableDeferred<Unit>()
            val attempts = AtomicInteger()
            val savedVotes = CopyOnWriteArrayList<Vote>()
            coEvery { repository.insertIgnoreAll(any()) } coAnswers {
                if (attempts.incrementAndGet() == 1) {
                    firstAttemptStarted.complete(Unit)
                    failFirstAttempt.await()
                    throw IllegalStateException("db down")
                }
                val votes = firstArg<Collection<Vote>>()
                savedVotes.addAll(votes)
                votes.size.toLong()
            }

            try {
                // When
                service.enqueue(clearedVote)
                awaitCondition { firstAttemptStarted.isCompleted }
                service.enqueue(queuedVote)
                service.clear()
                service.enqueue(newerVote)
                failFirstAttempt.complete(Unit)

                // Then
                awaitCondition { savedVotes.size == 1 && service.getBufferSize() == 0 }
                assertEquals(listOf(newerVote), savedVotes)
                coVerify(exactly = 1) { repository.insertIgnoreAll(listOf(clearedVote)) }
                coVerify(exactly = 0) { repository.insertIgnoreAll(match { queuedVote in it }) }
            } finally {
                failFirstAttempt.complete(Unit)
                service.stop()
            }
        }

    @Test
    fun `stopping the worker cancels persistence without retrying or dropping queued votes`() =
        runTest {
            // Given
            val repository = mockk<VoteRepository>()
            val service = VoteService(repository)
            val vote = Vote.sample()
            val attempts = AtomicInteger()
            val saveStarted = CompletableDeferred<Unit>()
            val saveCancelled = CompletableDeferred<Unit>()
            coEvery { repository.insertIgnoreAll(any()) } coAnswers {
                attempts.incrementAndGet()
                saveStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    saveCancelled.complete(Unit)
                }
            }

            try {
                // When
                service.enqueue(vote)
                awaitCondition { saveStarted.isCompleted }
                service.stop()

                // Then
                awaitCondition { saveCancelled.isCompleted && service.getBufferSize() == 1 }
                await()
                    .during(50, TimeUnit.MILLISECONDS)
                    .atMost(5, TimeUnit.SECONDS)
                    .until { attempts.get() == 1 }
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
