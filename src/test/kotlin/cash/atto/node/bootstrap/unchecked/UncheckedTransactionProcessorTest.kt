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
import cash.atto.node.bootstrap.discovery.DiscoveryProperties
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class UncheckedTransactionProcessorTest {
    @Test
    fun `skips repeated scans until work changes or maintenance is due`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.repository.findTopOldest(1_000L) } returns emptyFlow()

            // When
            val first = fixture.processor.processIfDue()
            val suppressed = fixture.processor.processIfDue()

            // Then
            assertEquals(UncheckedProcessingResult.Completed(0, 0), first)
            assertEquals(UncheckedProcessingResult.SkippedIdle, suppressed)
            coVerify(exactly = 1) { fixture.repository.findTopOldest(1_000L) }
            coVerify(exactly = 0) { fixture.service.cleanUp(any()) }

            // When
            fixture.workTracker.markChanged()
            fixture.processor.processIfDue()
            fixture.processor.processIfDue()
            fixture.clock.advance(Duration.ofSeconds(30))
            fixture.processor.processIfDue()

            // Then
            coVerify(exactly = 3) { fixture.repository.findTopOldest(1_000L) }
            coVerify(exactly = 0) { fixture.service.cleanUp(any()) }
        }

    @Test
    fun `cleanup uses the requested limit and records deleted rows`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.service.cleanUp(1_000L) } returnsMany listOf(1_000, 0)

            // When
            val cleanupProgress = fixture.processor.deleteExistingTransactions(1_000L)
            val completedCleanup = fixture.processor.deleteExistingTransactions(1_000L)

            // Then
            assertEquals(1_000, cleanupProgress)
            assertEquals(0, completedCleanup)
            coVerify(exactly = 2) { fixture.service.cleanUp(1_000L) }
            assertEquals(
                1_000.0,
                fixture.registry
                    .get("transactions.unchecked.cleanup.deleted")
                    .counter()
                    .count(),
            )
        }

    @Test
    fun `selects and commits resolution without owning cleanup scheduling`() =
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
            val result = fixture.processor.processIfDue()

            // Then
            assertEquals(UncheckedProcessingResult.Completed(1, 1), result)
            assertEquals(listOf("select", "begin", "commit"), timeline)
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, fixture.transactionManager.isolationLevel)
            assertEquals(1, fixture.transactionManager.commits)
            assertEquals(0, fixture.transactionManager.rollbacks)
            coVerify(exactly = 0) { fixture.service.cleanUp(any()) }
            coVerify(exactly = 1) {
                fixture.eventPublisher.publishAfterCommit(
                    match<TransactionResolved> { it.transaction == transaction },
                )
            }
        }

    @Test
    fun `resolution failure rolls back skips cleanup and releases the pass mutex`() =
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
                fixture.processor.processIfDue()
                fail("Expected resolution failure")
            } catch (_: IllegalStateException) {
                // Expected.
            }

            // Then
            assertEquals(0, fixture.transactionManager.commits)
            assertEquals(1, fixture.transactionManager.rollbacks)
            coVerify(exactly = 0) { fixture.service.cleanUp(any()) }

            // When
            val retry = fixture.processor.processIfDue()

            // Then
            assertEquals(UncheckedProcessingResult.Completed(1, 1), retry)
            assertEquals(1, fixture.transactionManager.commits)
            assertEquals(1, fixture.transactionManager.rollbacks)
            coVerify(exactly = 0) { fixture.service.cleanUp(any()) }
        }

    @Test
    fun `overlapping invocation skips instead of queuing another pass`() =
        runTest {
            // Given
            val fixture = fixture()
            val selectionStarted = CompletableDeferred<Unit>()
            val releaseSelection = CompletableDeferred<Unit>()
            coEvery { fixture.repository.findTopOldest(1_000L) } returns
                flow {
                    selectionStarted.complete(Unit)
                    releaseSelection.await()
                }

            // When
            val firstPass = async { fixture.processor.processIfDue() }
            selectionStarted.await()
            val overlap = fixture.processor.processIfDue()
            val cleanupOverlap = fixture.processor.deleteExistingTransactions(1_000L)

            // Then
            assertEquals(UncheckedProcessingResult.SkippedBusy, overlap)
            assertEquals(null, cleanupOverlap)
            coVerify(exactly = 1) { fixture.repository.findTopOldest(1_000L) }
            coVerify(exactly = 0) { fixture.service.cleanUp(any()) }

            // When
            releaseSelection.complete(Unit)
            val completed = firstPass.await()

            // Then
            assertEquals(UncheckedProcessingResult.Completed(0, 0), completed)
            coVerify(exactly = 0) { fixture.service.cleanUp(any()) }
        }

    private fun fixture(timeline: MutableList<String> = mutableListOf()): Fixture {
        val repository = mockk<UncheckedTransactionRepository>()
        val service = mockk<UncheckedTransactionService>()
        val workTracker = UncheckedWorkTracker()
        val clock = MutableClock()
        val accountRepository = mockk<AccountRepository>()
        val validationManager = mockk<TransactionValidationManager>()
        val accountService = mockk<AccountService>()
        val eventPublisher = mockk<EventPublisher>(relaxed = true)
        val transactionManager = RecordingReactiveTransactionManager(timeline)
        val registry = SimpleMeterRegistry()
        val processor =
            UncheckedTransactionProcessor(
                accountRepository = accountRepository,
                transactionValidationManager = validationManager,
                accountService = accountService,
                eventPublisher = eventPublisher,
                uncheckedTransactionRepository = repository,
                uncheckedTransactionService = service,
                workTracker = workTracker,
                discoveryProperties = DiscoveryProperties(),
                meterRegistry = registry,
                transactionManager = transactionManager,
                clock = clock,
            )
        return Fixture(
            processor = processor,
            repository = repository,
            service = service,
            workTracker = workTracker,
            clock = clock,
            accountRepository = accountRepository,
            validationManager = validationManager,
            accountService = accountService,
            eventPublisher = eventPublisher,
            transactionManager = transactionManager,
            registry = registry,
        )
    }

    private data class Fixture(
        val processor: UncheckedTransactionProcessor,
        val repository: UncheckedTransactionRepository,
        val service: UncheckedTransactionService,
        val workTracker: UncheckedWorkTracker,
        val clock: MutableClock,
        val accountRepository: AccountRepository,
        val validationManager: TransactionValidationManager,
        val accountService: AccountService,
        val eventPublisher: EventPublisher,
        val transactionManager: RecordingReactiveTransactionManager,
        val registry: SimpleMeterRegistry,
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

    private class MutableClock(
        private var current: Instant = Instant.parse("2026-08-03T00:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

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
