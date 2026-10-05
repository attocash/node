package cash.atto.node.account

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager
import org.springframework.transaction.reactive.GenericReactiveTransaction
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import reactor.core.publisher.Mono
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class AccountCachedRepositoryTest {
    @Test
    fun `should not restore stale head after delayed update follows ambiguous commit`() {
        // Given
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val previousHash = hash()
        val oldTransaction = transaction(publicKey, height = 5, previous = previousHash)
        val oldHead =
            account(publicKey, height = 5, lastTransactionHash = oldTransaction.hash).copy(
                balance = oldTransaction.block.balance,
                lastTransactionTimestamp = oldTransaction.block.timestamp.toJavaInstant(),
            )
        val newTransaction = transaction(publicKey, height = 6, previous = oldTransaction.hash)
        val updatedHead =
            oldHead.copy(
                balance = newTransaction.block.balance,
                lastTransactionHash = newTransaction.hash,
                lastTransactionTimestamp = newTransaction.block.timestamp.toJavaInstant(),
            )
        val fixture = CacheFixture(oldHead)
        val transactionManager =
            TestReactiveTransactionManager(
                onCommit = {
                    fixture.durableHead = checkNotNull(fixture.pendingHead)
                    throw IllegalStateException("Commit completed but its acknowledgement was lost")
                },
                onRollback = { fixture.pendingHead = null },
            )
        val transactionalOperator = TransactionalOperator.create(transactionManager)

        // When
        AnnotationConfigApplicationContext().use { context ->
            context.beanFactory.registerSingleton("accountCachedRepository", fixture.repository)
            context.refresh()

            val seededHead = runBlocking { fixture.repository.findById(publicKey) }
            assertEquals(oldHead, seededHead)
            assertThrows<RuntimeException> {
                runBlocking {
                    transactionalOperator.executeAndAwait {
                        fixture.repository.saveAll(listOf(updatedHead)).toList()
                    }
                }
            }

            // A delayed notification from an older commit must not repopulate the invalidated key.
            context.publishEvent(
                AccountUpdated(
                    TransactionSource.BOOTSTRAP,
                    oldHead.copy(
                        height = oldHead.height - 1,
                        balance = AttoAmount((oldHead.height - 1).toULong() * 100UL),
                        lastTransactionHash = previousHash,
                        lastTransactionTimestamp = oldHead.lastTransactionTimestamp.minusMillis(1),
                    ),
                    oldHead,
                    oldTransaction,
                ),
            )

            // Then
            val recoveredHead = runBlocking { fixture.repository.findById(publicKey) }
            assertEquals(oldHead.height + 1, fixture.durableHead.height)
            assertEquals(newTransaction.hash, fixture.durableHead.lastTransactionHash)
            assertEquals(fixture.durableHead, recoveredHead)
        }
    }

    @Test
    fun `should not expose a head when its transaction rolls back`() {
        // Given
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val oldHead = account(publicKey, height = 5, lastTransactionHash = hash())
        val fixture = CacheFixture(oldHead)
        val transactionManager =
            TestReactiveTransactionManager(
                onCommit = { fixture.durableHead = checkNotNull(fixture.pendingHead) },
                onRollback = { fixture.pendingHead = null },
            )
        val transactionalOperator = TransactionalOperator.create(transactionManager)
        val updatedHead = oldHead.copy(lastTransactionHash = hash())

        // When
        assertEquals(oldHead, runBlocking { fixture.repository.findById(publicKey) })
        assertThrows<IllegalArgumentException> {
            runBlocking {
                transactionalOperator.executeAndAwait {
                    fixture.repository.saveAll(listOf(updatedHead)).toList()
                    throw IllegalArgumentException("Roll back this account update")
                }
            }
        }

        // Then
        assertEquals(oldHead, runBlocking { fixture.repository.findById(publicKey) })
        assertEquals(oldHead, fixture.durableHead)
        assertEquals(2, fixture.databaseReads)
    }

    @Test
    fun `should publish saved head to cache after successful commit`() {
        // Given
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val oldHead = account(publicKey, height = 5, lastTransactionHash = hash())
        val fixture = CacheFixture(oldHead)
        val transactionManager =
            TestReactiveTransactionManager(
                onCommit = { fixture.durableHead = checkNotNull(fixture.pendingHead) },
                onRollback = { fixture.pendingHead = null },
            )
        val transactionalOperator = TransactionalOperator.create(transactionManager)
        val updatedHead = oldHead.copy(lastTransactionHash = hash())

        // When
        assertEquals(oldHead, runBlocking { fixture.repository.findById(publicKey) })
        runBlocking {
            transactionalOperator.executeAndAwait {
                fixture.repository.saveAll(listOf(updatedHead)).toList()
            }
        }

        // Then
        val cachedHead = runBlocking { fixture.repository.findById(publicKey) }
        assertEquals(oldHead.height + 1, cachedHead?.height)
        assertEquals(updatedHead.lastTransactionHash, cachedHead?.lastTransactionHash)
        assertEquals(1, fixture.databaseReads)
    }

    private class CacheFixture(
        initialHead: Account,
    ) {
        var durableHead = initialHead
        var pendingHead: Account? = null
        var databaseReads = 0
        private val publicKey = initialHead.publicKey
        private val accountCrudRepository = mockk<AccountCrudRepository>()
        val repository = AccountCachedRepository(accountCrudRepository)

        init {
            every { accountCrudRepository.findAllById(any<Iterable<AttoPublicKey>>()) } answers {
                val requestedKeys = firstArg<Iterable<AttoPublicKey>>().toSet()
                flow {
                    if (publicKey in requestedKeys) {
                        this@CacheFixture.databaseReads += 1
                        emit(this@CacheFixture.durableHead)
                    }
                }
            }
            coEvery { accountCrudRepository.upsertAll(any()) } coAnswers {
                this@CacheFixture.pendingHead = firstArg<Collection<Account>>().single()
                1L
            }
        }
    }

    private class TestReactiveTransactionManager(
        private val onCommit: () -> Unit,
        private val onRollback: () -> Unit,
    ) : AbstractReactiveTransactionManager() {
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
        ): Mono<Void> = Mono.fromRunnable { onCommit() }

        override fun doRollback(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction,
        ): Mono<Void> = Mono.fromRunnable { onRollback() }

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
        )
    }

    private fun account(
        publicKey: AttoPublicKey,
        height: Long,
        lastTransactionHash: AttoHash,
    ): Account =
        Account(
            publicKey = publicKey,
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            height = height,
            balance = AttoAmount.MIN,
            lastTransactionTimestamp = Instant.EPOCH,
            lastTransactionHash = lastTransactionHash,
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = publicKey,
            persistedAt = Instant.EPOCH,
        )

    private fun transaction(
        publicKey: AttoPublicKey,
        height: Long,
        previous: AttoHash,
    ): Transaction =
        Transaction(
            block =
                AttoReceiveBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = publicKey,
                    height = height.toULong().toAttoHeight(),
                    balance = AttoAmount(height.toULong() * 100UL),
                    timestamp = AttoInstant.now() + height.milliseconds,
                    previous = previous,
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = hash(),
                ),
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            work = AttoWork(Random.nextBytes(ByteArray(8))),
        )

    private fun hash() = AttoHash(Random.nextBytes(ByteArray(32)))
}
