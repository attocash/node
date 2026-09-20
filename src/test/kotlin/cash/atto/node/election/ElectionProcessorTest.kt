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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager
import org.springframework.transaction.reactive.GenericReactiveTransaction
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ElectionProcessorTest {
    @Test
    fun `worker persists queued consensus`() =
        runTest {
            // Given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val processor = newProcessor(accountService, transactionManager)
            val transaction = Transaction.sample()
            val attempts = AtomicInteger()
            coEvery { accountService.add(TransactionSource.ELECTION, listOf(transaction)) } coAnswers {
                attempts.incrementAndGet()
                emptyList()
            }

            try {
                // When
                processor.process(ElectionConsensusReached(mockk(relaxed = true), transaction, emptyList()))

                // Then
                awaitCondition { transactionManager.commits == 1 }
                assertEquals(0, processor.getBufferSize())
                assertEquals(1, attempts.get())
            } finally {
                processor.stop()
            }
        }

    @Test
    fun `worker requeues and retries consensus when persistence fails`() =
        runTest {
            // Given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val meterRegistry = SimpleMeterRegistry()
            val processor = newProcessor(accountService, transactionManager, meterRegistry)
            val transactions = listOf(Transaction.sample(), Transaction.sample())
            val attempts = AtomicInteger()
            val firstAttemptStarted = CompletableDeferred<Unit>()
            val releaseFirstAttempt = CompletableDeferred<Unit>()
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                if (attempts.incrementAndGet() == 1) {
                    firstAttemptStarted.complete(Unit)
                    releaseFirstAttempt.await()
                    throw IllegalStateException("db down")
                }
                emptyList()
            }

            try {
                // When
                processor.process(ElectionConsensusReached(mockk(relaxed = true), transactions.first(), emptyList()))
                firstAttemptStarted.await()
                processor.process(ElectionConsensusReached(mockk(relaxed = true), transactions.last(), emptyList()))
                releaseFirstAttempt.complete(Unit)

                // Then
                awaitCondition { transactionManager.commits == 1 && transactionManager.rollbacks == 1 }
                assertEquals(2, attempts.get())
                assertEquals(0, processor.getBufferSize())
                assertPhaseCounts(meterRegistry, 1)
            } finally {
                processor.stop()
            }
        }

    @Test
    fun `partitions successful persistence through transaction cleanup`() =
        runTest {
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
            val processor = newProcessor(accountService, transactionManager, meterRegistry)
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
            val startedAt = metricClock.monotonicTime()

            try {
                // When
                processor.process(ElectionConsensusReached(mockk(relaxed = true), Transaction.sample(), emptyList()))

                // Then
                awaitCondition { meterRegistry.get("elections.processor.batch").timer().count() == 1L }
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
            } finally {
                processor.stop()
            }
        }

    @Test
    fun `does not record persistence phases when commit fails before retry`() =
        runTest {
            // Given
            val commitAttempts = AtomicInteger()
            val transactionManager =
                RecordingReactiveTransactionManager(
                    onCommit = {
                        if (commitAttempts.incrementAndGet() == 1) {
                            throw IllegalStateException("commit failed")
                        }
                    },
                )
            val accountService = mockk<AccountService>()
            val meterRegistry = SimpleMeterRegistry()
            val processor = newProcessor(accountService, transactionManager, meterRegistry)
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } returns emptyList()

            try {
                // When
                processor.process(ElectionConsensusReached(mockk(relaxed = true), Transaction.sample(), emptyList()))

                // Then
                awaitCondition { commitAttempts.get() == 2 }
                assertEquals(0, processor.getBufferSize())
                assertEquals(1, transactionManager.rollbacks)
                assertPhaseCounts(meterRegistry, 1)
                assertEquals(1, meterRegistry.get("elections.processor.batch").timer().count())
            } finally {
                processor.stop()
            }
        }

    @Test
    fun `persists sequential consensus events in FIFO order`() =
        runTest {
            // Given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val processor = newProcessor(accountService, transactionManager)
            val firstTransaction = Transaction.sample()
            val secondTransaction = Transaction.sample()
            val savedBatches = CopyOnWriteArrayList<List<Transaction>>()
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                savedBatches += secondArg<List<Transaction>>()
                emptyList()
            }

            try {
                // When
                processor.process(ElectionConsensusReached(mockk(relaxed = true), firstTransaction, emptyList()))
                processor.process(ElectionConsensusReached(mockk(relaxed = true), secondTransaction, emptyList()))

                // Then
                awaitCondition { processor.getBufferSize() == 0 && savedBatches.sumOf { it.size } == 2 }
                assertEquals(
                    listOf(firstTransaction.hash, secondTransaction.hash),
                    savedBatches.flatten().map { it.hash },
                )
            } finally {
                processor.stop()
            }
        }

    private fun newProcessor(
        accountService: AccountService,
        transactionManager: ReactiveTransactionManager,
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
    ): ElectionProcessor =
        ElectionProcessor(
            messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true),
            accountService = accountService,
            meterRegistry = meterRegistry,
            transactionManager = transactionManager,
        ).also { it.start() }

    private fun awaitCondition(condition: () -> Boolean) {
        await()
            .atMost(5, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.MILLISECONDS)
            .until(condition)
    }

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
        private val commitCount = AtomicInteger()
        private val rollbackCount = AtomicInteger()

        val commits: Int
            get() = commitCount.get()

        val rollbacks: Int
            get() = rollbackCount.get()

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
                commitCount.incrementAndGet()
                onCommit()
            }

        override fun doRollback(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction,
        ): Mono<Void> = Mono.fromRunnable { rollbackCount.incrementAndGet() }

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
            @Volatile var active: Boolean = false,
        )
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
