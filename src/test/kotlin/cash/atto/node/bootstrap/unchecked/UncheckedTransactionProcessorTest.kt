package cash.atto.node.bootstrap.unchecked

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoVersion
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.AccountService
import cash.atto.node.bootstrap.TransactionResolved
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.transaction.validation.TransactionValidationManager
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import reactor.core.publisher.Mono

class UncheckedTransactionProcessorTest {
    @Test
    fun `executes every time it is called`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.repository.findTopOldest(1_000L) } returns emptyFlow()

            // When
            val first = fixture.processor.process()
            val second = fixture.processor.process()

            // Then
            assertEquals(0, first)
            assertEquals(0, second)
            coVerify(exactly = 2) { fixture.repository.findTopOldest(1_000L) }
        }

    @Test
    fun `returns the number of resolved transactions`() =
        runTest {
            // Given
            val timeline = mutableListOf<String>()
            val fixture = fixture(timeline)
            val unchecked = mockk<UncheckedTransaction>()
            val transaction = Transaction.sample()
            val account = mockk<Account>()

            every { unchecked.toTransaction() } returns transaction
            every { account.algorithm } returns transaction.algorithm
            coEvery { fixture.repository.findTopOldest(1_000L) } returns
                flow {
                    timeline += "select"
                    emit(unchecked)
                }
            coEvery { fixture.accountRepository.findById(transaction.publicKey) } returns account
            coEvery { fixture.validationManager.validate(account, transaction) } returns null
            coEvery {
                fixture.accountService.add(TransactionSource.BOOTSTRAP, listOf(transaction))
            } returns listOf(account)

            // When
            val resolved = fixture.processor.process()

            // Then
            assertEquals(1, resolved)
            assertEquals(listOf("select", "begin", "commit"), timeline)
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, fixture.transactionManager.isolationLevel)
            assertEquals(1, fixture.transactionManager.commits)
            assertEquals(0, fixture.transactionManager.rollbacks)
            coVerify(exactly = 1) {
                fixture.eventPublisher.publishAfterCommit(
                    match<TransactionResolved> { it.transaction == transaction },
                )
            }
        }

    @Test
    fun `resolution failure rolls back and a later call executes again`() =
        runTest {
            // Given
            val fixture = fixture()
            val unchecked = mockk<UncheckedTransaction>()
            val transaction = Transaction.sample()
            val account = mockk<Account>()
            var validationAttempts = 0

            every { unchecked.toTransaction() } returns transaction
            every { account.algorithm } returns transaction.algorithm
            coEvery { fixture.repository.findTopOldest(1_000L) } returns flowOf(unchecked)
            coEvery { fixture.accountRepository.findById(transaction.publicKey) } returns account
            coEvery { fixture.validationManager.validate(account, transaction) } coAnswers {
                validationAttempts++
                if (validationAttempts == 1) {
                    error("simulated resolution failure")
                }
                null
            }
            coEvery {
                fixture.accountService.add(TransactionSource.BOOTSTRAP, listOf(transaction))
            } returns listOf(account)

            // When
            try {
                fixture.processor.process()
                fail("Expected resolution failure")
            } catch (_: IllegalStateException) {
                // Expected.
            }
            val retry = fixture.processor.process()

            // Then
            assertEquals(1, retry)
            assertEquals(1, fixture.transactionManager.commits)
            assertEquals(1, fixture.transactionManager.rollbacks)
        }

    @Test
    fun `overlapping invocations both execute without worker-level locking`() =
        runTest {
            // Given
            val fixture = fixture()
            val selectionStarted = CompletableDeferred<Unit>()
            val releaseSelection = CompletableDeferred<Unit>()
            coEvery { fixture.repository.findTopOldest(1_000L) } returnsMany
                listOf(
                    flow {
                        selectionStarted.complete(Unit)
                        releaseSelection.await()
                    },
                    emptyFlow(),
                )

            // When
            val firstPass = async { fixture.processor.process() }
            selectionStarted.await()
            val overlap = fixture.processor.process()

            // Then
            assertEquals(0, overlap)
            coVerify(exactly = 2) { fixture.repository.findTopOldest(1_000L) }

            // When
            releaseSelection.complete(Unit)

            // Then
            assertEquals(0, firstPass.await())
        }

    private fun fixture(timeline: MutableList<String> = mutableListOf()): Fixture {
        val repository = mockk<UncheckedTransactionRepository>()
        val accountRepository = mockk<AccountRepository>()
        val validationManager = mockk<TransactionValidationManager>()
        val accountService = mockk<AccountService>()
        val eventPublisher = mockk<EventPublisher>(relaxed = true)
        val transactionManager = RecordingReactiveTransactionManager(timeline)
        val processor =
            UncheckedTransactionProcessor(
                accountRepository = accountRepository,
                transactionValidationManager = validationManager,
                accountService = accountService,
                eventPublisher = eventPublisher,
                uncheckedTransactionRepository = repository,
                meterRegistry = SimpleMeterRegistry(),
                transactionManager = transactionManager,
            )
        return Fixture(
            processor = processor,
            repository = repository,
            accountRepository = accountRepository,
            validationManager = validationManager,
            accountService = accountService,
            eventPublisher = eventPublisher,
            transactionManager = transactionManager,
        )
    }

    private data class Fixture(
        val processor: UncheckedTransactionProcessor,
        val repository: UncheckedTransactionRepository,
        val accountRepository: AccountRepository,
        val validationManager: TransactionValidationManager,
        val accountService: AccountService,
        val eventPublisher: EventPublisher,
        val transactionManager: RecordingReactiveTransactionManager,
    )

    private class RecordingReactiveTransactionManager(
        private val timeline: MutableList<String>,
    ) : ReactiveTransactionManager {
        var commits = 0
            private set
        var rollbacks = 0
            private set
        var isolationLevel = TransactionDefinition.ISOLATION_DEFAULT
            private set

        override fun getReactiveTransaction(definition: TransactionDefinition?): Mono<ReactiveTransaction> {
            isolationLevel = definition?.isolationLevel ?: TransactionDefinition.ISOLATION_DEFAULT
            timeline += "begin"
            return Mono.just(SimpleReactiveTransaction)
        }

        override fun commit(transaction: ReactiveTransaction): Mono<Void> =
            Mono.fromRunnable {
                commits++
                timeline += "commit"
            }

        override fun rollback(transaction: ReactiveTransaction): Mono<Void> =
            Mono.fromRunnable {
                rollbacks++
                timeline += "rollback"
            }
    }

    private object SimpleReactiveTransaction : ReactiveTransaction

    private fun Transaction.Companion.sample(): Transaction =
        Transaction(
            block =
                AttoReceiveBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(ByteArray(32) { 1 }),
                    height = cash.atto.commons.AttoHeight(2UL),
                    balance = AttoAmount.MAX,
                    timestamp = AttoInstant.now(),
                    previous = AttoHash(ByteArray(32) { 2 }),
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = AttoHash(ByteArray(32) { 3 }),
                ),
            signature = AttoSignature(ByteArray(64) { 4 }),
            work = AttoWork(ByteArray(8) { 5 }),
        )
}
