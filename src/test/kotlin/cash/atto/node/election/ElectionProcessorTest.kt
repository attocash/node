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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
import kotlin.random.Random

class ElectionProcessorTest {
    @Test
    fun `backs off queued consensus events when persistence fails`() =
        runBlocking {
            // given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val clock = MutableClock()
            val processor = newProcessor(accountService, transactionManager, clock)
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

            // when
            processor.flush()

            // then
            assertEquals(2, processor.getBufferSize())
            assertEquals(1, attempts)
            assertEquals(0, transactionManager.commits)
            assertEquals(1, transactionManager.rollbacks)

            // when
            processor.flush()

            // then
            assertEquals(2, processor.getBufferSize())
            assertEquals(1, attempts)
            assertEquals(0, transactionManager.commits)
            assertEquals(1, transactionManager.rollbacks)

            // when
            clock.advance(Duration.ofSeconds(1))
            processor.flush()

            // then
            assertEquals(0, processor.getBufferSize())
            assertEquals(2, attempts)
            assertEquals(1, transactionManager.commits)
            assertEquals(1, transactionManager.rollbacks)
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
    fun `drops consensus event after retry limit`() =
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
                            processingRetryMaxAttempts = 3
                        },
                )
            val transaction = Transaction.sample()
            var attempts = 0

            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                attempts++
                throw IllegalStateException("invalid transaction")
            }

            processor.process(ElectionConsensusReached(mockk(relaxed = true), transaction, emptyList()))

            // when
            processor.flush()
            clock.advance(Duration.ofSeconds(1))
            processor.flush()
            clock.advance(Duration.ofSeconds(1))
            processor.flush()
            clock.advance(Duration.ofSeconds(1))
            processor.flush()

            // then
            assertEquals(0, processor.getBufferSize())
            assertEquals(3, attempts)
            assertEquals(0, transactionManager.commits)
            assertEquals(3, transactionManager.rollbacks)
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
    ): ElectionProcessor =
        ElectionProcessor(
            messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true),
            accountService = accountService,
            properties = properties,
            meterRegistry = SimpleMeterRegistry(),
            transactionManager = transactionManager,
            clock = clock,
        ).also { it.start() }

    private class RecordingReactiveTransactionManager : ReactiveTransactionManager {
        var commits = 0
            private set
        var rollbacks = 0
            private set

        override fun getReactiveTransaction(definition: TransactionDefinition?): Mono<ReactiveTransaction> =
            Mono.just(SimpleReactiveTransaction)

        override fun commit(transaction: ReactiveTransaction): Mono<Void> = Mono.fromRunnable<Void> { commits++ }

        override fun rollback(transaction: ReactiveTransaction): Mono<Void> = Mono.fromRunnable<Void> { rollbacks++ }
    }

    private object SimpleReactiveTransaction : ReactiveTransaction

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
