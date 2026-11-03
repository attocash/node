package cash.atto.node.transaction.validation

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
import cash.atto.commons.toJavaInstant
import cash.atto.node.Event
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountRepository
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionReceived
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.TransactionValidated
import cash.atto.node.transaction.TransactionValidationFailed
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TransactionValidationManagerTest {
    private val block =
        AttoReceiveBlock(
            version = 0U.toAttoVersion(),
            network = AttoNetwork.LOCAL,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(ByteArray(32) { 1 }),
            height = AttoHeight(2UL),
            balance = AttoAmount(1UL),
            timestamp = AttoInstant.now(),
            previous = AttoHash(ByteArray(32) { 2 }),
            sendHashAlgorithm = AttoAlgorithm.V1,
            sendHash = AttoHash(ByteArray(32) { 3 }),
        )
    private val transaction =
        Transaction(
            block = block,
            signature = AttoSignature(ByteArray(64) { 4 }),
            work = AttoWork(ByteArray(8) { 5 }),
        )
    private val account =
        Account(
            publicKey = block.publicKey,
            network = block.network,
            version = block.version,
            algorithm = block.algorithm,
            height = 1,
            balance = AttoAmount.MIN,
            lastTransactionHash = block.previous,
            lastTransactionTimestamp = block.timestamp.toJavaInstant().minusSeconds(1),
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = block.publicKey,
        )
    private val accountRepository = mockk<AccountRepository>()
    private val validator = mockk<TransactionValidator>()
    private val eventPublisher = mockk<EventPublisher>()
    private val published = mutableListOf<Event>()
    private val manager = TransactionValidationManager(accountRepository, listOf(validator), eventPublisher)

    init {
        coEvery { accountRepository.findById(transaction.publicKey) } returns account
        every { validator.supports(transaction) } returns true
        coEvery { validator.validate(account, transaction) } returns null
        every { eventPublisher.publish(any()) } answers {
            published += firstArg<Event>()
        }
    }

    @Test
    fun `publishes validation failure when account lookup fails`() =
        runTest {
            // Given
            val cause = IllegalStateException("Account lookup failed")
            coEvery { accountRepository.findById(transaction.publicKey) } throws cause

            // When
            manager.process(TransactionReceived(transaction))

            // Then
            val failure = assertInstanceOf(TransactionValidationFailed::class.java, published.single())
            assertSame(transaction, failure.transaction)
            assertSame(cause, failure.cause)
            coVerify(exactly = 0) { validator.validate(any(), any()) }
        }

    @Test
    fun `publishes validation failure when a validator fails`() =
        runTest {
            // Given
            val cause = IllegalStateException("Receivable lookup failed")
            coEvery { validator.validate(account, transaction) } throws cause

            // When
            manager.process(TransactionReceived(transaction))

            // Then
            val failure = assertInstanceOf(TransactionValidationFailed::class.java, published.single())
            assertSame(transaction, failure.transaction)
            assertSame(cause, failure.cause)
        }

    @Test
    fun `propagates account lookup cancellation without publishing failure`() =
        runTest {
            // Given
            val cancellation = CancellationException("Account lookup cancelled")
            coEvery { accountRepository.findById(transaction.publicKey) } throws cancellation

            // When
            val thrown = assertThrows<CancellationException> { manager.process(TransactionReceived(transaction)) }

            // Then
            assertSame(cancellation, thrown)
            assertTrue(published.isEmpty())
        }

    @Test
    fun `propagates validator cancellation without publishing failure`() =
        runTest {
            // Given
            val cancellation = CancellationException("Validation cancelled")
            coEvery { validator.validate(account, transaction) } throws cancellation

            // When
            val thrown = assertThrows<CancellationException> { manager.process(TransactionReceived(transaction)) }

            // Then
            assertSame(cancellation, thrown)
            assertTrue(published.isEmpty())
        }

    @Test
    fun `publishes the first validation violation as a rejection`() =
        runTest {
            // Given
            val violation = TransactionViolation(TransactionRejectionReason.RECEIVABLE_NOT_FOUND, "Receivable is missing")
            coEvery { validator.validate(account, transaction) } returns violation
            val nextValidator = mockk<TransactionValidator>()
            val manager = TransactionValidationManager(accountRepository, listOf(validator, nextValidator), eventPublisher)

            // When
            manager.process(TransactionReceived(transaction))

            // Then
            val rejection = assertInstanceOf(TransactionRejected::class.java, published.single())
            assertEquals(violation.reason, rejection.reason)
            assertEquals(violation.message, rejection.message)
            assertSame(account, rejection.account)
            assertSame(transaction, rejection.transaction)
            coVerify(exactly = 0) { nextValidator.validate(any(), any()) }
        }

    @Test
    fun `publishes validated transaction after successful validation`() =
        runTest {
            // Given
            val event = TransactionReceived(transaction)

            // When
            manager.process(event)

            // Then
            val validated = assertInstanceOf(TransactionValidated::class.java, published.single())
            assertSame(account, validated.account)
            assertSame(transaction, validated.transaction)
        }

    @Test
    fun `does not report validated event publication exceptions as validation failures`() =
        runTest {
            // Given
            val cause = IllegalStateException("Validated event publication failed")
            every { eventPublisher.publish(any()) } answers {
                published += firstArg<Event>()
                throw cause
            }

            // When
            val thrown = assertThrows<IllegalStateException> { manager.process(TransactionReceived(transaction)) }

            // Then
            assertSame(cause, thrown)
            assertInstanceOf(TransactionValidated::class.java, published.single())
        }

    @Test
    fun `does not report rejected event publication exceptions as validation failures`() =
        runTest {
            // Given
            coEvery { validator.validate(account, transaction) } returns
                TransactionViolation(TransactionRejectionReason.RECEIVABLE_NOT_FOUND, "Receivable is missing")
            val cause = IllegalStateException("Rejected event publication failed")
            every { eventPublisher.publish(any()) } answers {
                published += firstArg<Event>()
                throw cause
            }

            // When
            val thrown = assertThrows<IllegalStateException> { manager.process(TransactionReceived(transaction)) }

            // Then
            assertSame(cause, thrown)
            assertInstanceOf(TransactionRejected::class.java, published.single())
        }
}
