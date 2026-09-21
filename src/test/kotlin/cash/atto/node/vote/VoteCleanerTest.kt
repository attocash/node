package cash.atto.node.vote

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toAttoVersion
import cash.atto.node.account.Account
import cash.atto.node.account.AccountUpdated
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class VoteCleanerTest {
    @Test
    fun `worker deletes queued votes after one coalescing wait`() =
        runTest {
            // Given
            val blockHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val repository = mockk<VoteRepository>()
            val cleaner = VoteCleaner(repository, Clock.systemUTC())
            coEvery { repository.deleteByBlockHashes(listOf(blockHash)) } returns 1

            try {
                // When
                cleaner.process(accountUpdated(blockHash))

                // Then
                awaitCondition { cleaner.getBufferSize() == 0 }
                coVerify(exactly = 1) { repository.deleteByBlockHashes(listOf(blockHash)) }
            } finally {
                cleaner.stop()
            }
        }

    @Test
    fun `worker retries retained cleanup without another delay`() =
        runTest {
            // Given
            val blockHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val repository = mockk<VoteRepository>()
            val cleaner = VoteCleaner(repository, Clock.systemUTC())
            val attempts = AtomicInteger()
            coEvery { repository.deleteByBlockHashes(listOf(blockHash)) } coAnswers {
                if (attempts.incrementAndGet() == 1) {
                    throw IllegalStateException("deadlock")
                }
                1
            }

            try {
                // When
                cleaner.process(accountUpdated(blockHash))

                // Then
                awaitCondition { cleaner.getBufferSize() == 0 && attempts.get() == 2 }
            } finally {
                cleaner.stop()
            }
        }

    @Test
    fun `does not delete votes when account tip is unchanged`() =
        runTest {
            // Given
            val account = sampleAccount(AttoHash(Random.nextBytes(ByteArray(32))))
            val repository = mockk<VoteRepository>()
            val cleaner = VoteCleaner(repository, Clock.systemUTC())

            try {
                // When
                cleaner.process(
                    AccountUpdated(
                        TransactionSource.ELECTION,
                        account,
                        account,
                        mockk<Transaction>(),
                    ),
                )

                // Then
                coVerify(exactly = 0) { repository.deleteByBlockHashes(any()) }
                assertEquals(0, cleaner.getBufferSize())
            } finally {
                cleaner.stop()
            }
        }

    @Test
    fun `worker retries stale block hashes in FIFO order when deletion fails`() =
        runTest {
            // Given
            val firstHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val secondHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val repository = mockk<VoteRepository>()
            val cleaner = VoteCleaner(repository, Clock.systemUTC())
            val attemptedBatches = CopyOnWriteArrayList<List<AttoHash>>()
            val attempts = AtomicInteger()
            val firstAttemptStarted = CompletableDeferred<Unit>()
            val releaseFirstAttempt = CompletableDeferred<Unit>()
            coEvery { repository.deleteByBlockHashes(any()) } coAnswers {
                attemptedBatches += firstArg<Collection<AttoHash>>().toList()
                if (attempts.incrementAndGet() == 1) {
                    firstAttemptStarted.complete(Unit)
                    releaseFirstAttempt.await()
                    throw IllegalStateException("deadlock")
                }
                2
            }

            try {
                // When
                cleaner.process(accountUpdated(firstHash))
                firstAttemptStarted.await()
                cleaner.process(accountUpdated(secondHash))
                releaseFirstAttempt.complete(Unit)

                // Then
                awaitCondition { cleaner.getBufferSize() == 0 && attempts.get() == 2 }
                assertEquals(
                    listOf(
                        listOf(firstHash),
                        listOf(firstHash, secondHash),
                    ),
                    attemptedBatches,
                )
            } finally {
                cleaner.stop()
            }
        }

    @Test
    fun `deletes stale votes using account tips during startup`() {
        // Given
        val clock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())
        val cutoff = Instant.EPOCH.minus(Duration.ofMinutes(5))
        val repository = mockk<VoteRepository>()
        val cleaner = VoteCleaner(repository, clock)
        coEvery { repository.deleteStale(cutoff) } returns 3

        try {
            // When
            cleaner.deleteStaleVotesOnStartup()

            // Then
            coVerify(exactly = 1) { repository.deleteStale(cutoff) }
        } finally {
            cleaner.stop()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        await()
            .atMost(5, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.MILLISECONDS)
            .until(condition)
    }

    private fun accountUpdated(previousHash: AttoHash): AccountUpdated {
        val previousAccount = sampleAccount(previousHash)
        return AccountUpdated(
            TransactionSource.ELECTION,
            previousAccount,
            previousAccount.copy(lastTransactionHash = AttoHash(Random.nextBytes(ByteArray(32)))),
            mockk<Transaction>(),
        )
    }

    private fun sampleAccount(lastTransactionHash: AttoHash): Account =
        Account(
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            height = 1,
            balance = AttoAmount.MIN,
            lastTransactionTimestamp = Instant.EPOCH,
            lastTransactionHash = lastTransactionHash,
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            persistedAt = Instant.EPOCH,
        )
}
