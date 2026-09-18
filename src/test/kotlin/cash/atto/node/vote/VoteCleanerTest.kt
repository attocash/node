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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

class VoteCleanerTest {
    @Test
    fun `deletes votes for previous account tip when account advances`() =
        runTest {
            // given
            val previousHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val previousAccount = sampleAccount(previousHash)
            val updatedAccount = previousAccount.copy(lastTransactionHash = AttoHash(Random.nextBytes(ByteArray(32))))
            val repository = mockk<VoteRepository>()
            val cleaner = VoteCleaner(repository, Clock.systemUTC())
            coEvery { repository.deleteByBlockHashes(listOf(previousHash)) } returns 3

            // when
            cleaner.process(
                AccountUpdated(
                    TransactionSource.ELECTION,
                    previousAccount,
                    updatedAccount,
                    mockk<Transaction>(),
                ),
            )
            cleaner.flush()

            // then
            coVerify(exactly = 1) { repository.deleteByBlockHashes(listOf(previousHash)) }
            assertEquals(0, cleaner.getBufferSize())
        }

    @Test
    fun `does not delete votes when account tip is unchanged`() =
        runTest {
            // given
            val account = sampleAccount(AttoHash(Random.nextBytes(ByteArray(32))))
            val repository = mockk<VoteRepository>()
            val cleaner = VoteCleaner(repository, Clock.systemUTC())

            // when
            cleaner.process(
                AccountUpdated(
                    TransactionSource.ELECTION,
                    account,
                    account,
                    mockk<Transaction>(),
                ),
            )

            // then
            coVerify(exactly = 0) { repository.deleteByBlockHashes(any()) }
            assertEquals(0, cleaner.getBufferSize())
        }

    @Test
    fun `requeues stale block hashes in FIFO order when deletion fails`() =
        runTest {
            // given
            val firstHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val secondHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val repository = mockk<VoteRepository>()
            val cleaner = VoteCleaner(repository, Clock.systemUTC())
            cleaner.process(accountUpdated(firstHash))
            cleaner.process(accountUpdated(secondHash))
            coEvery { repository.deleteByBlockHashes(listOf(firstHash, secondHash)) } throws
                IllegalStateException("deadlock")

            // when
            val failure = runCatching { cleaner.flush() }.exceptionOrNull()

            // then
            assertEquals("deadlock", failure?.message)
            assertEquals(2, cleaner.getBufferSize())

            // given
            coEvery { repository.deleteByBlockHashes(listOf(firstHash, secondHash)) } returns 2

            // when
            cleaner.flush()

            // then
            assertEquals(0, cleaner.getBufferSize())
            coVerify(exactly = 2) { repository.deleteByBlockHashes(listOf(firstHash, secondHash)) }
        }

    @Test
    fun `deletes stale votes using account tips during startup`() =
        runTest {
            // given
            val clock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())
            val cutoff = Instant.EPOCH.minus(Duration.ofMinutes(5))
            val repository = mockk<VoteRepository>()
            val cleaner = VoteCleaner(repository, clock)
            coEvery { repository.deleteStale(cutoff) } returns 3

            // when
            cleaner.deleteStaleVotesOnStartup()

            // then
            coVerify(exactly = 1) { repository.deleteStale(cutoff) }
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
