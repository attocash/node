package cash.atto.node.account

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
import cash.atto.node.EventPublisher
import cash.atto.node.account.entry.AccountEntryService
import cash.atto.node.receivable.ReceivableRepository
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionService
import cash.atto.node.transaction.TransactionSource
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.Instant
import kotlin.random.Random

class AccountServiceTest {
    @Test
    fun `rejects multiple transactions for the same public key in one batch`() {
        // given
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val service =
            AccountService(
                thisNode = sampleNode(),
                accountRepository = mockk(),
                accountEntryService = mockk(),
                transactionService = mockk(),
                receivableRepository = mockk(),
                eventPublisher = mockk(),
            )
        val firstTransaction = Transaction.sample(publicKey = publicKey, height = AttoHeight(2UL))
        val secondTransaction = Transaction.sample(publicKey = publicKey, height = AttoHeight(3UL))

        // when
        val exception =
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    service.add(TransactionSource.ELECTION, listOf(firstTransaction, secondTransaction))
                }
            }

        // then
        assertEquals(
            "Cannot add multiple transactions for the same public key in one account batch: $publicKey",
            exception.message,
        )
    }

    @Test
    fun `publishes account updated when account advances`() {
        // given
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val previousHash = AttoHash(Random.nextBytes(ByteArray(32)))
        val account =
            Account(
                publicKey = publicKey,
                network = AttoNetwork.LOCAL,
                version = 0U.toAttoVersion(),
                algorithm = AttoAlgorithm.V1,
                height = 1,
                balance = AttoAmount.MIN,
                lastTransactionTimestamp = Instant.EPOCH,
                lastTransactionHash = previousHash,
                representativeAlgorithm = AttoAlgorithm.V1,
                representativePublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
                persistedAt = Instant.EPOCH,
            )
        val transaction = Transaction.sample(publicKey = publicKey, height = AttoHeight(2UL))
        val updatedAccount =
            account.copy(
                height = 2,
                balance = transaction.block.balance,
                lastTransactionHash = transaction.hash,
                updatedAt = Instant.EPOCH,
            )
        val accountRepository = mockk<AccountRepository>()
        val transactionService = mockk<TransactionService>()
        val eventPublisher = mockk<EventPublisher>(relaxed = true)
        val service =
            AccountService(
                thisNode = sampleNode(),
                accountRepository = accountRepository,
                accountEntryService = mockk<AccountEntryService>(relaxed = true),
                transactionService = transactionService,
                receivableRepository = mockk<ReceivableRepository>(relaxed = true),
                eventPublisher = eventPublisher,
            )

        every { accountRepository.findAllById(listOf(publicKey)) } returns flowOf(account)
        every { accountRepository.saveAll(any()) } returns flowOf(updatedAccount)
        coEvery { transactionService.saveAll(listOf(transaction)) } returns Unit

        // when
        val accounts =
            runBlocking {
                service.add(TransactionSource.ELECTION, listOf(transaction))
            }

        // then
        assertEquals(listOf(updatedAccount), accounts)
        coVerify(exactly = 1) {
            eventPublisher.publishAfterCommit(
                match { event ->
                    event is AccountUpdated &&
                        event.source == TransactionSource.ELECTION &&
                        event.previousAccount == account &&
                        event.updatedAccount == updatedAccount &&
                        event.transaction == transaction
                },
            )
        }
    }

    private fun sampleNode(): AttoNode =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0U.toUShort(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            publicUri = URI("ws://127.0.0.1:8081"),
            features = setOf(NodeFeature.VOTING),
        )

    private fun Transaction.Companion.sample(
        publicKey: AttoPublicKey,
        height: AttoHeight,
    ): Transaction =
        Transaction(
            block =
                AttoReceiveBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = publicKey,
                    height = height,
                    balance = AttoAmount.MAX,
                    timestamp = AttoInstant.now(),
                    previous = AttoHash(Random.nextBytes(ByteArray(32))),
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
                ),
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            work = AttoWork(Random.nextBytes(ByteArray(8))),
        )
}
