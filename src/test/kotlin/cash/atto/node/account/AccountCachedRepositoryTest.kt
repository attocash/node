package cash.atto.node.account

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
import kotlinx.coroutines.flow.flowOf
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

class AccountCachedRepositoryTest {
    @Test
    fun `save updates shared cache only after commit`() =
        runTest {
            // given
            val original = sampleAccount(height = 1)
            val crud = mockk<AccountCrudRepository>()
            val repository = AccountCachedRepository(crud)
            coEvery { crud.upsertAll(any()) } returns 1
            every { crud.findAllById(any<Iterable<AttoPublicKey>>()) } returns flowOf(original)
            assertEquals(original, repository.findById(original.publicKey))
            every { crud.findAllById(any<Iterable<AttoPublicKey>>()) } returns emptyFlow()

            // when
            var staged: Account? = null
            reactiveTransaction(commit = false) {
                staged = repository.saveAll(listOf(original)).single()
                assertEquals(staged, repository.findById(original.publicKey))
            }

            // then
            assertEquals(original, repository.findById(original.publicKey))

            // when
            reactiveTransaction {
                staged = repository.saveAll(listOf(original)).single()
            }

            // then
            assertEquals(staged, repository.findById(original.publicKey))
        }

    @Test
    fun `failed save leaves existing cache unchanged`() =
        runTest {
            // given
            val original = sampleAccount(height = 1)
            val crud = mockk<AccountCrudRepository>()
            val repository = AccountCachedRepository(crud)
            every { crud.findAllById(any<Iterable<AttoPublicKey>>()) } returns flowOf(original)
            assertEquals(original, repository.findById(original.publicKey))
            every { crud.findAllById(any<Iterable<AttoPublicKey>>()) } returns emptyFlow()
            coEvery { crud.upsertAll(any()) } throws IllegalStateException("write failed")

            // when
            assertThrows<IllegalStateException> {
                reactiveTransactionBlocking {
                    repository.saveAll(listOf(original.copy(height = 2))).single()
                }
            }

            // then
            assertEquals(original, repository.findById(original.publicKey))
        }

    @Test
    fun `delete all is transaction local and rollback or failure preserves cache`() =
        runTest {
            // given
            val original = sampleAccount(height = 1)
            val crud = mockk<AccountCrudRepository>()
            val repository = AccountCachedRepository(crud)
            every { crud.findAllById(any<Iterable<AttoPublicKey>>()) } returns flowOf(original)
            assertEquals(original, repository.findById(original.publicKey))
            every { crud.findAllById(any<Iterable<AttoPublicKey>>()) } returns emptyFlow()
            coEvery { crud.deleteAll() } returns Unit

            // when
            reactiveTransaction(commit = false) {
                repository.deleteAll()
                assertNull(repository.findById(original.publicKey))
            }

            // then
            assertEquals(original, repository.findById(original.publicKey))

            // when
            coEvery { crud.deleteAll() } throws IllegalStateException("delete failed")
            assertThrows<IllegalStateException> {
                kotlinx.coroutines.runBlocking { repository.deleteAll() }
            }

            // then
            assertEquals(original, repository.findById(original.publicKey))

            // when
            coEvery { crud.deleteAll() } returns Unit
            reactiveTransaction { repository.deleteAll() }

            // then
            assertNull(repository.findById(original.publicKey))
        }

    @Test
    fun `save after transactional clear is visible and wins at commit`() =
        runTest {
            // given
            val original = sampleAccount(height = 1)
            val replacement = original.copy(height = 5)
            val crud = mockk<AccountCrudRepository>()
            val repository = AccountCachedRepository(crud)
            every { crud.findAllById(any<Iterable<AttoPublicKey>>()) } returns flowOf(original)
            assertEquals(original, repository.findById(original.publicKey))
            every { crud.findAllById(any<Iterable<AttoPublicKey>>()) } returns emptyFlow()
            coEvery { crud.deleteAll() } returns Unit
            coEvery { crud.upsertAll(any()) } returns 1

            // when
            lateinit var saved: Account
            reactiveTransaction {
                repository.deleteAll()
                assertNull(repository.findById(original.publicKey))
                saved = repository.saveAll(listOf(replacement)).single()
                assertEquals(saved, repository.findById(original.publicKey))
            }

            // then
            assertEquals(saved, repository.findById(original.publicKey))
        }

    private fun sampleAccount(height: Long): Account =
        Account(
            publicKey = AttoPublicKey(ByteArray(32) { 1 }),
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            height = height,
            balance = AttoAmount.MIN,
            lastTransactionTimestamp = Instant.EPOCH,
            lastTransactionHash = AttoHash(ByteArray(32) { 2 }),
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = AttoPublicKey(ByteArray(32) { 3 }),
            persistedAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
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
