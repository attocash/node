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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.reactive.TransactionContextManager
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import java.time.Instant

class ReceivableCachedRepositoryTest {
    @Test
    fun `save updates shared cache only after commit`() =
        runTest {
            // given
            val original = sampleReceivable(1)
            val crud = mockk<ReceivableCrudRepository>()
            val repository = ReceivableCachedRepository(crud)
            coEvery { crud.insertAll(any()) } returns 1
            every { crud.findAllById(any<Iterable<AttoHash>>()) } returns emptyFlow()
            lateinit var committed: Receivable
            reactiveTransaction {
                committed = repository.saveAll(listOf(original)).single()
            }
            val replacement = original.copy(amount = AttoAmount.MAX)

            // when
            reactiveTransaction(commit = false) {
                val staged = repository.saveAll(listOf(replacement)).single()
                assertEquals(staged, repository.findById(original.hash))
            }

            // then
            assertEquals(committed, repository.findById(original.hash))

            // when
            lateinit var updated: Receivable
            reactiveTransaction {
                updated = repository.saveAll(listOf(replacement)).single()
            }

            // then
            assertEquals(updated, repository.findById(original.hash))
        }

    @Test
    fun `failed save leaves existing cache unchanged`() =
        runTest {
            // given
            val original = sampleReceivable(1)
            val crud = mockk<ReceivableCrudRepository>()
            val repository = ReceivableCachedRepository(crud)
            every { crud.findAllById(any<Iterable<AttoHash>>()) } returns emptyFlow()
            coEvery { crud.insertAll(any()) } returns 1
            lateinit var committed: Receivable
            reactiveTransaction {
                committed = repository.saveAll(listOf(original)).single()
            }
            coEvery { crud.insertAll(any()) } throws IllegalStateException("write failed")

            // when
            assertThrows<IllegalStateException> {
                reactiveTransactionBlocking {
                    repository.saveAll(listOf(original.copy(amount = AttoAmount.MAX))).single()
                }
            }

            // then
            assertEquals(committed, repository.findById(original.hash))
        }

    @Test
    fun `keyed delete is transaction local and rollback or failure preserves cache`() =
        runTest {
            // given
            val original = sampleReceivable(1)
            val crud = mockk<ReceivableCrudRepository>()
            val repository = ReceivableCachedRepository(crud)
            every { crud.findAllById(any<Iterable<AttoHash>>()) } returns emptyFlow()
            coEvery { crud.insertAll(any()) } returns 1
            reactiveTransaction { repository.saveAll(listOf(original)).single() }
            coEvery { crud.deleteAllByHash(any()) } returns 1

            // when
            reactiveTransaction(commit = false) {
                repository.deleteAllByHash(listOf(original.hash, original.hash))
                assertNull(repository.findById(original.hash))
            }

            // then
            val cached = repository.findById(original.hash)
            assertEquals(original.hash, cached?.hash)

            // when
            coEvery { crud.deleteAllByHash(any()) } throws IllegalStateException("delete failed")
            assertThrows<IllegalStateException> {
                kotlinx.coroutines.runBlocking { repository.deleteAllByHash(listOf(original.hash)) }
            }

            // then
            assertEquals(cached, repository.findById(original.hash))

            // when
            coEvery { crud.deleteAllByHash(any()) } returns 1
            reactiveTransaction { repository.deleteAllByHash(listOf(original.hash)) }

            // then
            assertNull(repository.findById(original.hash))
        }

    @Test
    fun `delete all is transaction local and rollback or failure preserves cache`() =
        runTest {
            // given
            val original = sampleReceivable(1)
            val crud = mockk<ReceivableCrudRepository>()
            val repository = ReceivableCachedRepository(crud)
            every { crud.findAllById(any<Iterable<AttoHash>>()) } returns emptyFlow()
            coEvery { crud.insertAll(any()) } returns 1
            reactiveTransaction { repository.saveAll(listOf(original)).single() }
            coEvery { crud.deleteAll() } returns Unit

            // when
            reactiveTransaction(commit = false) {
                repository.deleteAll()
                assertNull(repository.findById(original.hash))
            }

            // then
            val cached = repository.findById(original.hash)
            assertEquals(original.hash, cached?.hash)

            // when
            coEvery { crud.deleteAll() } throws IllegalStateException("delete failed")
            assertThrows<IllegalStateException> {
                kotlinx.coroutines.runBlocking { repository.deleteAll() }
            }

            // then
            assertEquals(cached, repository.findById(original.hash))

            // when
            coEvery { crud.deleteAll() } returns Unit
            reactiveTransaction { repository.deleteAll() }

            // then
            assertNull(repository.findById(original.hash))
        }

    @Test
    fun `mixed operations apply the final value for each key`() =
        runTest {
            // given
            val deletedAfterSave = sampleReceivable(1)
            val savedAfterClear = sampleReceivable(2)
            val crud = mockk<ReceivableCrudRepository>()
            val repository = ReceivableCachedRepository(crud)
            every { crud.findAllById(any<Iterable<AttoHash>>()) } returns emptyFlow()
            coEvery { crud.insertAll(any()) } returns 1
            coEvery { crud.deleteAllByHash(any()) } returns 1
            coEvery { crud.deleteAll() } returns Unit

            // when
            lateinit var committed: Receivable
            reactiveTransaction {
                repository.saveAll(listOf(deletedAfterSave)).single()
                repository.deleteAllByHash(listOf(deletedAfterSave.hash))
                repository.deleteAll()
                committed = repository.saveAll(listOf(savedAfterClear)).single()
                assertNull(repository.findById(deletedAfterSave.hash))
                assertEquals(committed, repository.findById(savedAfterClear.hash))
            }

            // then
            assertNull(repository.findById(deletedAfterSave.hash))
            assertEquals(committed, repository.findById(savedAfterClear.hash))
        }

    private fun sampleReceivable(seed: Int): Receivable =
        Receivable(
            hash = AttoHash(ByteArray(32) { seed.toByte() }),
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(ByteArray(32) { (seed + 1).toByte() }),
            timestamp = Instant.EPOCH,
            receiverAlgorithm = AttoAlgorithm.V1,
            receiverPublicKey = AttoPublicKey(ByteArray(32) { (seed + 2).toByte() }),
            amount = AttoAmount.MIN,
            persistedAt = Instant.EPOCH,
        )
}

private fun reactiveTransactionBlocking(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking {
        reactiveTransaction(block = block)
    }
}

private suspend fun reactiveTransaction(
    commit: Boolean = true,
    block: suspend () -> Unit,
) {
    mono {
        val manager = TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
        manager.initSynchronization()
        try {
            block()
            completeTransaction(manager, commit)
        } catch (exception: Throwable) {
            completeTransaction(manager, commit = false)
            throw exception
        } finally {
            manager.clearSynchronization()
            manager.clear()
        }
    }.contextWrite(TransactionContextManager.createTransactionContext()).awaitFirstOrNull()
}

private suspend fun completeTransaction(
    manager: TransactionSynchronizationManager,
    commit: Boolean,
) {
    val synchronizations = manager.synchronizations
    synchronizations.forEach { it.beforeCompletion().awaitFirstOrNull() }
    if (commit) {
        synchronizations.forEach { it.afterCommit().awaitFirstOrNull() }
    }
    val status =
        if (commit) {
            TransactionSynchronization.STATUS_COMMITTED
        } else {
            TransactionSynchronization.STATUS_ROLLED_BACK
        }
    synchronizations.forEach { it.afterCompletion(status).awaitFirstOrNull() }
}
