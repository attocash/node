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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

class StaleVoteBlockRecorderTest {
    @Test
    fun `records previous account hash when account advances`() =
        runTest {
            // given
            val previousHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val updatedHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val previousAccount = account(lastTransactionHash = previousHash)
            val updatedAccount = previousAccount.copy(lastTransactionHash = updatedHash, height = previousAccount.height + 1)
            val service = mockk<StaleVoteBlockService>()
            val recorder = StaleVoteBlockRecorder(service)

            every { service.record(previousHash) } returns Unit

            // when
            recorder.process(AccountUpdated(TransactionSource.ELECTION, previousAccount, updatedAccount, mockk<Transaction>()))

            // then
            verify(exactly = 1) { service.record(previousHash) }
        }

    @Test
    fun `does not record stale hash when account hash is unchanged`() =
        runTest {
            // given
            val hash = AttoHash(Random.nextBytes(ByteArray(32)))
            val previousAccount = account(lastTransactionHash = hash)
            val updatedAccount = previousAccount.copy(height = previousAccount.height + 1)
            val service = mockk<StaleVoteBlockService>()
            val recorder = StaleVoteBlockRecorder(service)

            // when
            recorder.process(AccountUpdated(TransactionSource.ELECTION, previousAccount, updatedAccount, mockk<Transaction>()))

            // then
            verify(exactly = 0) { service.record(any()) }
        }

    private fun account(lastTransactionHash: AttoHash): Account =
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
