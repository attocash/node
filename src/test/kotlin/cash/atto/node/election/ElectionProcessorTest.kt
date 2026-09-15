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
import cash.atto.node.account.AccountService
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import io.micrometer.core.instrument.MockClock
import io.micrometer.core.instrument.simple.SimpleConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager
import org.springframework.transaction.reactive.GenericReactiveTransaction
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ElectionProcessorTest {
    @Test
    fun `backs off queued consensus events when persistence fails`() =
        runBlocking {
            // Given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val clock = MutableClock()
            val meterRegistry = SimpleMeterRegistry()
            val processor = newProcessor(accountService, transactionManager, clock, meterRegistry = meterRegistry)
            val transactions =
                listOf(
                    Transaction.sample(),
                    Transaction.sample(),
                )
            var attempts = 0

            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                attempts++
                if (attempts == 1) {
                    throw IllegalStateException("db down")
                }
                emptyList()
            }

            transactions.forEach {
                processor.process(ElectionConsensusReached(mockk(relaxed = true), it, emptyList()))
            }

            // When
            processor.flush()

            // Then
            assertEquals(2, processor.getBufferSize())
            assertEquals(1, attempts)
            assertEquals(0, transactionManager.commits)
            assertEquals(1, transactionManager.rollbacks)
            assertPhaseCounts(meterRegistry, 0)

            // When
            processor.flush()

            // Then
            assertEquals(2, processor.getBufferSize())
            assertEquals(1, attempts)
            assertEquals(0, transactionManager.commits)
            assertEquals(1, transactionManager.rollbacks)

            // When
            clock.advance(Duration.ofSeconds(1))
            processor.flush()

            // Then
            assertEquals(0, processor.getBufferSize())
            assertEquals(2, attempts)
            assertEquals(1, transactionManager.commits)
            assertEquals(1, transactionManager.rollbacks)
            assertPhaseCounts(meterRegistry, 1)
        }

    @Test
    fun `partitions successful persistence through transaction cleanup`() =
        runBlocking {
            // Given
            val metricClock = MockClock()
            val meterRegistry = SimpleMeterRegistry(SimpleConfig.DEFAULT, metricClock)
            val transactionManager =
                RecordingReactiveTransactionManager(
                    onBegin = { metricClock.add(Duration.ofMillis(3)) },
                    onCommit = { metricClock.add(Duration.ofMillis(11)) },
                    onCleanup = { metricClock.add(Duration.ofMillis(19)) },
                )
            val accountService = mockk<AccountService>()
            val processor = newProcessor(accountService, transactionManager, meterRegistry = meterRegistry)
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                metricClock.add(Duration.ofMillis(7))
                TransactionSynchronizationManager.forCurrentTransaction().awaitSingle().registerSynchronization(
                    object : TransactionSynchronization, Ordered {
                        override fun getOrder(): Int = 0

                        override fun beforeCommit(readOnly: Boolean): Mono<Void> =
                            Mono.fromRunnable { metricClock.add(Duration.ofMillis(2)) }

                        override fun afterCommit(): Mono<Void> = Mono.fromRunnable { metricClock.add(Duration.ofMillis(13)) }

                        override fun afterCompletion(status: Int): Mono<Void> = Mono.fromRunnable { metricClock.add(Duration.ofMillis(17)) }
                    },
                )
                emptyList()
            }
            processor.process(ElectionConsensusReached(mockk(relaxed = true), Transaction.sample(), emptyList()))
            val startedAt = metricClock.monotonicTime()

            // When
            processor.flush()

            // Then
            val expectedMilliseconds =
                mapOf(
                    "acquire_begin" to 3.0,
                    "body" to 7.0,
                    "commit" to 13.0,
                    "after_commit" to 13.0,
                    "cleanup" to 36.0,
                )
            expectedMilliseconds.forEach { (phase, expected) ->
                val timer = meterRegistry.get("elections.persistence.phase").tag("phase", phase).timer()
                assertEquals(1, timer.count())
                assertEquals(expected, timer.totalTime(TimeUnit.MILLISECONDS), phase)
            }
            val totalNanoseconds =
                meterRegistry.get("elections.persistence.phase").timers().sumOf { it.totalTime(TimeUnit.NANOSECONDS) }
            assertEquals((metricClock.monotonicTime() - startedAt).toDouble(), totalNanoseconds)
            assertEquals(1, meterRegistry.get("elections.processor.batch").timer().count())
            assertEquals(0, processor.getBufferSize())
        }

    @Test
    fun `does not record persistence phases when commit fails and retries after backoff`() =
        runBlocking {
            // Given
            var commitAttempts = 0
            val transactionManager =
                RecordingReactiveTransactionManager(
                    onCommit = {
                        commitAttempts++
                        if (commitAttempts == 1) throw IllegalStateException("commit failed")
                    },
                )
            val accountService = mockk<AccountService>()
            val meterRegistry = SimpleMeterRegistry()
            val clock = MutableClock()
            val processor = newProcessor(accountService, transactionManager, clock, meterRegistry = meterRegistry)
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } returns emptyList()
            processor.process(ElectionConsensusReached(mockk(relaxed = true), Transaction.sample(), emptyList()))

            // When
            processor.flush()
            processor.flush()

            // Then
            assertEquals(1, processor.getBufferSize())
            assertEquals(1, commitAttempts)
            assertEquals(1, transactionManager.rollbacks)
            assertPhaseCounts(meterRegistry, 0)
            assertEquals(0, meterRegistry.get("elections.processor.batch").timer().count())

            // When
            clock.advance(Duration.ofSeconds(1))
            processor.flush()

            // Then
            assertEquals(0, processor.getBufferSize())
            assertEquals(2, commitAttempts)
            assertPhaseCounts(meterRegistry, 1)
            assertEquals(1, meterRegistry.get("elections.processor.batch").timer().count())
        }

    @Test
    fun `skips phase observations when persistence joins an outer transaction`() =
        runBlocking {
            // Given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val meterRegistry = SimpleMeterRegistry()
            val processor = newProcessor(accountService, transactionManager, meterRegistry = meterRegistry)
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } returns emptyList()
            processor.process(ElectionConsensusReached(mockk(relaxed = true), Transaction.sample(), emptyList()))

            // When
            TransactionalOperator.create(transactionManager).executeAndAwait {
                processor.flush()
            }

            // Then
            assertEquals(1, transactionManager.commits)
            assertEquals(0, transactionManager.rollbacks)
            assertEquals(0, processor.getBufferSize())
            assertPhaseCounts(meterRegistry, 0)
        }

    @Test
    fun `persists drained consensus events in one batch`() =
        runBlocking {
            // given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val processor = newProcessor(accountService, transactionManager)
            val firstTransaction = Transaction.sample()
            val secondTransaction = Transaction.sample()
            val savedBatches = mutableListOf<List<Transaction>>()

            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                savedBatches += secondArg<List<Transaction>>()
                emptyList()
            }

            processor.process(ElectionConsensusReached(mockk(relaxed = true), firstTransaction, emptyList()))
            processor.process(ElectionConsensusReached(mockk(relaxed = true), secondTransaction, emptyList()))

            // when
            processor.flush()

            // then
            assertEquals(0, processor.getBufferSize())
            assertEquals(
                listOf(
                    listOf(firstTransaction.hash, secondTransaction.hash),
                ),
                savedBatches.map { batch -> batch.map { it.hash } },
            )
            assertEquals(1, transactionManager.commits)
            assertEquals(0, transactionManager.rollbacks)
        }

    @Test
    fun `keeps consensus event queued after repeated persistence failures`() =
        runBlocking {
            // given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val clock = MutableClock()
            val processor =
                newProcessor(
                    accountService,
                    transactionManager,
                    clock,
                    properties =
                        ElectionProperties().apply {
                            processingRetryMaxBackoffInSeconds = 1
                        },
                )
            val transaction = Transaction.sample()
            var attempts = 0

            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                attempts++
                throw IllegalStateException("db down")
            }

            processor.process(ElectionConsensusReached(mockk(relaxed = true), transaction, emptyList()))

            // when
            repeat(6) {
                processor.flush()
                clock.advance(Duration.ofSeconds(1))
            }

            // then
            assertEquals(1, processor.getBufferSize())
            assertEquals(6, attempts)
            assertEquals(0, transactionManager.commits)
            assertEquals(6, transactionManager.rollbacks)
        }

    @Test
    fun `backoff gate skips queue work until retry time`() =
        runBlocking {
            // given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val clock = MutableClock()
            val processor = newProcessor(accountService, transactionManager, clock)
            val delayedTransaction = Transaction.sample()
            val dueTransaction = Transaction.sample()
            val savedBatches = mutableListOf<List<Transaction>>()
            var attempts = 0

            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                attempts++
                val transactions = secondArg<List<Transaction>>()
                savedBatches += transactions
                if (attempts == 1) {
                    throw IllegalStateException("db down")
                }
                emptyList()
            }

            processor.process(ElectionConsensusReached(mockk(relaxed = true), delayedTransaction, emptyList()))
            processor.flush()
            processor.process(ElectionConsensusReached(mockk(relaxed = true), dueTransaction, emptyList()))

            // when
            processor.flush()

            // then
            assertEquals(2, processor.getBufferSize())
            assertEquals(1, attempts)

            // when
            clock.advance(Duration.ofSeconds(1))
            processor.flush()

            // then
            assertEquals(0, processor.getBufferSize())
            assertEquals(2, attempts)
            assertEquals(
                listOf(
                    listOf(delayedTransaction.hash),
                    listOf(delayedTransaction.hash, dueTransaction.hash),
                ),
                savedBatches.map { batch -> batch.map { it.hash } },
            )
            assertEquals(1, transactionManager.commits)
            assertEquals(1, transactionManager.rollbacks)
        }

    private fun newProcessor(
        accountService: AccountService,
        transactionManager: ReactiveTransactionManager,
        clock: Clock = MutableClock(),
        properties: ElectionProperties = ElectionProperties(),
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
    ): ElectionProcessor =
        ElectionProcessor(
            messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true),
            accountService = accountService,
            properties = properties,
            meterRegistry = meterRegistry,
            transactionManager = transactionManager,
            clock = clock,
        ).also { it.start() }

    private fun assertPhaseCounts(
        meterRegistry: SimpleMeterRegistry,
        expected: Long,
    ) {
        val timers = meterRegistry.get("elections.persistence.phase").timers()
        assertEquals(5, timers.size)
        timers.forEach { assertEquals(expected, it.count(), it.id.getTag("phase")) }
    }

    private class RecordingReactiveTransactionManager(
        private val onBegin: () -> Unit = {},
        private val onCommit: () -> Unit = {},
        private val onCleanup: () -> Unit = {},
    ) : AbstractReactiveTransactionManager() {
        var commits = 0
            private set
        var rollbacks = 0
            private set

        override fun doGetTransaction(synchronizationManager: TransactionSynchronizationManager): Any =
            synchronizationManager.getResource(this) ?: TestTransaction()

        override fun isExistingTransaction(transaction: Any): Boolean = (transaction as TestTransaction).active

        override fun doBegin(
            synchronizationManager: TransactionSynchronizationManager,
            transaction: Any,
            definition: TransactionDefinition,
        ): Mono<Void> =
            Mono.fromRunnable {
                onBegin()
                (transaction as TestTransaction).active = true
                synchronizationManager.bindResource(this, transaction)
            }

        override fun doCommit(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction,
        ): Mono<Void> =
            Mono.fromRunnable {
                commits++
                onCommit()
            }

        override fun doRollback(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction,
        ): Mono<Void> = Mono.fromRunnable { rollbacks++ }

        override fun doCleanupAfterCompletion(
            synchronizationManager: TransactionSynchronizationManager,
            transaction: Any,
        ): Mono<Void> =
            Mono.fromRunnable {
                onCleanup()
                (transaction as TestTransaction).active = false
                synchronizationManager.unbindResource(this)
            }

        private class TestTransaction(
            var active: Boolean = false,
        )
    }

    private class MutableClock(
        private var current: Instant = Instant.parse("2026-07-02T00:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private fun Transaction.Companion.sample(
        publicKey: AttoPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
        height: AttoHeight = AttoHeight(2UL),
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
