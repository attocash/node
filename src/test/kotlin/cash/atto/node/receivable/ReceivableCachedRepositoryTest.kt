package cash.atto.node.receivable

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toAttoVersion
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager
import org.springframework.transaction.reactive.GenericReactiveTransaction
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ReceivableCachedRepositoryTest {
    @Test
    fun `delayed send completion cannot restore a receivable deleted after commit`() =
        runBlocking {
            // Given
            val receivable = receivable()
            val transactionManager = TestReactiveTransactionManager()
            val fixture = RepositoryFixture(transactionManager)
            val transactionalOperator = TransactionalOperator.create(transactionManager)
            val afterCompletionEntered = CompletableFuture<Unit>()
            val releaseAfterCompletion = CompletableFuture<Unit>()
            val send =
                async {
                    transactionalOperator.executeAndAwait {
                        fixture.repository.saveAll(listOf(receivable)).toList()
                        TransactionSynchronizationManager
                            .forCurrentTransaction()
                            .awaitSingle()
                            .registerSynchronization(
                                object : TransactionSynchronization, Ordered {
                                    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

                                    override fun afterCompletion(status: Int): Mono<Void> =
                                        if (status == TransactionSynchronization.STATUS_COMMITTED) {
                                            Mono
                                                .fromRunnable<Void> { afterCompletionEntered.complete(Unit) }
                                                .then(Mono.fromFuture(releaseAfterCompletion).then())
                                        } else {
                                            Mono.empty()
                                        }
                                },
                            )
                    }
                }

            // When
            try {
                withTimeout(5_000) { Mono.fromFuture(afterCompletionEntered).awaitSingle() }
                assertEquals(receivable.hash, fixture.durableReceivable(receivable.hash)?.hash)
                transactionalOperator.executeAndAwait {
                    fixture.repository.deleteAllByHash(listOf(receivable.hash))
                }
                assertNull(fixture.durableReceivable(receivable.hash))
                releaseAfterCompletion.complete(Unit)
                send.await()
            } finally {
                releaseAfterCompletion.complete(Unit)
            }

            // Then
            assertNull(fixture.repository.findById(receivable.hash))
        }

    @Test
    fun `committed send is cached without another database read`() =
        runBlocking {
            // Given
            val receivable = receivable()
            val transactionManager = TestReactiveTransactionManager()
            val fixture = RepositoryFixture(transactionManager)
            val transactionalOperator = TransactionalOperator.create(transactionManager)

            // When
            val saved =
                transactionalOperator.executeAndAwait {
                    fixture.repository
                        .saveAll(listOf(receivable))
                        .toList()
                        .single()
                }

            // Then
            assertEquals(saved, fixture.repository.findById(receivable.hash))
            assertEquals(0, fixture.databaseReads.get())
        }

    @Test
    fun `rolled back send is not visible`() =
        runBlocking {
            // Given
            val receivable = receivable()
            val transactionManager = TestReactiveTransactionManager()
            val fixture = RepositoryFixture(transactionManager)
            val transactionalOperator = TransactionalOperator.create(transactionManager)

            // When
            var rollbackMessage: String? = null
            try {
                transactionalOperator.executeAndAwait {
                    fixture.repository.saveAll(listOf(receivable)).toList()
                    throw IllegalStateException("roll back send")
                }
            } catch (exception: IllegalStateException) {
                rollbackMessage = exception.message
            }

            // Then
            assertEquals("roll back send", rollbackMessage)
            assertNull(fixture.repository.findById(receivable.hash))
            assertEquals(1, fixture.databaseReads.get())
        }

    @Test
    fun `rolled back delete reloads the existing receivable`() =
        runBlocking {
            // Given
            val receivable = receivable()
            val transactionManager = TestReactiveTransactionManager()
            val fixture = RepositoryFixture(transactionManager)
            val transactionalOperator = TransactionalOperator.create(transactionManager)
            val saved =
                transactionalOperator.executeAndAwait {
                    fixture.repository
                        .saveAll(listOf(receivable))
                        .toList()
                        .single()
                }

            // When
            var rollbackMessage: String? = null
            try {
                transactionalOperator.executeAndAwait {
                    fixture.repository.deleteAllByHash(listOf(receivable.hash))
                    throw IllegalStateException("roll back delete")
                }
            } catch (exception: IllegalStateException) {
                rollbackMessage = exception.message
            }

            // Then
            assertEquals("roll back delete", rollbackMessage)
            assertEquals(saved, fixture.repository.findById(receivable.hash))
            assertEquals(1, fixture.databaseReads.get())
        }

    private class RepositoryFixture(
        private val transactionManager: TestReactiveTransactionManager,
    ) {
        private val durable = ConcurrentHashMap<AttoHash, Receivable>()
        private val receivableCrudRepository = mockk<ReceivableCrudRepository>()
        val databaseReads = AtomicInteger()
        val repository = ReceivableCachedRepository(receivableCrudRepository)

        fun durableReceivable(hash: AttoHash): Receivable? = durable[hash]

        init {
            every { receivableCrudRepository.findAllById(any<Iterable<AttoHash>>()) } answers {
                val hashes = firstArg<Iterable<AttoHash>>().toList()
                flow {
                    databaseReads.incrementAndGet()
                    hashes.forEach { hash -> durable[hash]?.let { emit(it) } }
                }
            }
            coEvery { receivableCrudRepository.insertAll(any()) } coAnswers {
                val receivables = firstArg<Collection<Receivable>>()
                transactionManager.stageCommit {
                    receivables.forEach { durable[it.hash] = it }
                }
                receivables.size.toLong()
            }
            coEvery { receivableCrudRepository.deleteAllByHash(any()) } coAnswers {
                val hashes = firstArg<Iterable<AttoHash>>().toList()
                val deletedCount = hashes.count(durable::containsKey)
                transactionManager.stageCommit { hashes.forEach(durable::remove) }
                deletedCount
            }
        }
    }

    private class TestReactiveTransactionManager : AbstractReactiveTransactionManager() {
        suspend fun stageCommit(action: () -> Unit) {
            val transaction =
                TransactionSynchronizationManager
                    .forCurrentTransaction()
                    .awaitSingle()
                    .getResource(this) as TestTransaction
            transaction.commitActions += action
        }

        override fun doGetTransaction(synchronizationManager: TransactionSynchronizationManager): Any =
            synchronizationManager.getResource(this) ?: TestTransaction()

        override fun isExistingTransaction(transaction: Any): Boolean = (transaction as TestTransaction).active

        override fun doBegin(
            synchronizationManager: TransactionSynchronizationManager,
            transaction: Any,
            definition: TransactionDefinition,
        ): Mono<Void> =
            Mono.fromRunnable {
                (transaction as TestTransaction).active = true
                synchronizationManager.bindResource(this, transaction)
            }

        override fun doCommit(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction,
        ): Mono<Void> =
            Mono.fromRunnable {
                val transaction = synchronizationManager.getResource(this) as TestTransaction
                transaction.commitActions.forEach { it() }
            }

        override fun doRollback(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction,
        ): Mono<Void> = Mono.empty()

        override fun doCleanupAfterCompletion(
            synchronizationManager: TransactionSynchronizationManager,
            transaction: Any,
        ): Mono<Void> =
            Mono.fromRunnable {
                (transaction as TestTransaction).active = false
                synchronizationManager.unbindResource(this)
            }

        private class TestTransaction(
            @Volatile var active: Boolean = false,
        ) {
            val commitActions = mutableListOf<() -> Unit>()
        }
    }

    private fun receivable(): Receivable =
        Receivable(
            hash = AttoHash(Random.nextBytes(ByteArray(32))),
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            timestamp = Instant.EPOCH,
            receiverAlgorithm = AttoAlgorithm.V1,
            receiverPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            amount = AttoAmount(100UL),
        )
}
