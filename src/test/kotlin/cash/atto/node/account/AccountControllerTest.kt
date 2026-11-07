package cash.atto.node.account

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoOpenBlock
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.node.AccountSearch
import cash.atto.commons.toAtto
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AccountControllerTest {
    private val repository = mockk<AccountRepository>()
    private val controller = AccountController(repository, mockk())

    @Test
    fun `subscribes before starting a blocked database read and retains a concurrent update`() =
        runTest {
            // Given
            val snapshot = opened().updatedAccount
            val update = received(snapshot)
            val finishRead = CompletableDeferred<Unit>()
            lateinit var publication: Deferred<Unit>
            every { repository.findAllById(any()) } answers {
                publication = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { controller.process(update) }
                flow {
                    finishRead.await()
                    emit(snapshot)
                }
            }
            val accounts = mutableListOf<AttoAccount>()

            // When
            backgroundScope.launch { controller.stream(snapshot.publicKey).toList(accounts) }
            runCurrent()

            // Then
            assertFalse(publication.isCompleted)
            assertTrue(accounts.isEmpty())

            // When
            finishRead.complete(Unit)
            runCurrent()

            // Then
            publication.await()
            assertEquals(listOf(snapshot, update.updatedAccount).map { it.toAttoAccount() }, accounts)
        }

    @Test
    fun `drops equal and older snapshot overlaps and delayed updates after newer live state`() =
        runTest {
            // Given
            val opened = opened()
            val second = received(opened.updatedAccount)
            val third = received(second.updatedAccount)
            val fourth = received(third.updatedAccount)
            val fifth = received(fourth.updatedAccount)
            val finishRead = CompletableDeferred<Unit>()
            every { repository.findAllById(any()) } returns
                flow {
                    emit(third.updatedAccount)
                    finishRead.await()
                }
            val accounts = mutableListOf<AttoAccount>()
            backgroundScope.launch { controller.stream(third.updatedAccount.publicKey).toList(accounts) }
            runCurrent()

            // When
            val older = backgroundScope.async { controller.process(second) }
            val equal = backgroundScope.async { controller.process(third) }
            val newer = backgroundScope.async { controller.process(fourth) }
            runCurrent()
            finishRead.complete(Unit)
            runCurrent()
            older.await()
            equal.await()
            newer.await()
            controller.process(third)
            controller.process(second)
            controller.process(fifth)
            runCurrent()

            // Then
            assertEquals(listOf(third, fourth, fifth).map { it.updatedAccount.toAttoAccount() }, accounts)
        }

    @Test
    fun `streams the first committed update for an unopened account`() =
        runTest {
            // Given
            val opened = opened()
            every { repository.findAllById(any()) } returns emptyFlow()
            val accounts = mutableListOf<AttoAccount>()
            backgroundScope.launch { controller.stream(opened.updatedAccount.publicKey).toList(accounts) }
            runCurrent()

            // When
            controller.process(opened)
            runCurrent()

            // Then
            assertEquals(listOf(opened.updatedAccount.toAttoAccount()), accounts)
        }

    @Test
    fun `each collection of the same flow owns its forward heights`() =
        runTest {
            // Given
            val snapshot = opened().updatedAccount
            val second = received(snapshot)
            val third = received(second.updatedAccount)
            every { repository.findAllById(any()) } returns flowOf(snapshot)
            val stream = controller.stream(snapshot.publicKey)
            val firstAccounts = mutableListOf<AttoAccount>()
            val secondAccounts = mutableListOf<AttoAccount>()
            val first = backgroundScope.launch { stream.toList(firstAccounts) }
            runCurrent()
            controller.process(second)
            runCurrent()

            // When
            backgroundScope.launch { stream.toList(secondAccounts) }
            runCurrent()
            first.cancelAndJoin()
            controller.process(third)
            runCurrent()

            // Then
            assertEquals(listOf(snapshot, second.updatedAccount).map { it.toAttoAccount() }, firstAccounts)
            assertEquals(listOf(snapshot, third.updatedAccount).map { it.toAttoAccount() }, secondAccounts)
        }

    @Test
    fun `cancelling a blocked snapshot releases the live publisher and cancels the database read`() =
        runTest {
            // Given
            val snapshot = opened().updatedAccount
            val readCancelled = CompletableDeferred<Unit>()
            every { repository.findAllById(any()) } returns
                flow {
                    try {
                        emit(snapshot)
                        awaitCancellation()
                    } finally {
                        readCancelled.complete(Unit)
                    }
                }
            val accounts = mutableListOf<AttoAccount>()
            val collection = backgroundScope.launch { controller.stream(snapshot.publicKey).toList(accounts) }
            runCurrent()
            val publication = backgroundScope.async { controller.process(received(snapshot)) }
            runCurrent()
            assertFalse(publication.isCompleted)

            // When
            collection.cancelAndJoin()
            runCurrent()

            // Then
            publication.await()
            assertTrue(readCancelled.isCompleted)
            assertEquals(listOf(snapshot.toAttoAccount()), accounts)
        }

    @Test
    fun `filters unrelated live accounts and tracks heights independently for each requested address`() =
        runTest {
            // Given
            val first = opened(1).updatedAccount
            val second = opened(2).updatedAccount
            val unrelated = opened(3)
            val firstNext = received(first)
            val secondNext = received(second)
            every { repository.findAllById(listOf(first.publicKey, second.publicKey)) } returns flowOf(first, second)
            val search = AccountSearch(linkedSetOf(first, second).map { AttoAddress(it.algorithm, it.publicKey) }.toSet())
            val accounts = mutableListOf<AttoAccount>()
            backgroundScope.launch { controller.stream(search).toList(accounts) }
            runCurrent()

            // When
            controller.process(unrelated)
            controller.process(firstNext)
            controller.process(secondNext)
            runCurrent()

            // Then
            assertEquals(listOf(first, second, firstNext.updatedAccount, secondNext.updatedAccount).map { it.toAttoAccount() }, accounts)
        }

    private fun opened(key: Int = 1): AccountUpdated {
        val publicKey = AttoPublicKey(ByteArray(32) { key.toByte() })
        val timestamp = Instant.EPOCH.plusSeconds(1)
        val block =
            AttoOpenBlock(
                version = 0U.toAttoVersion(),
                network = AttoNetwork.LOCAL,
                algorithm = AttoAlgorithm.V1,
                publicKey = publicKey,
                balance = AttoAmount(100UL),
                timestamp = timestamp.toAtto(),
                sendHashAlgorithm = AttoAlgorithm.V1,
                sendHash = AttoHash(ByteArray(32) { key.toByte() }),
                representativeAlgorithm = AttoAlgorithm.V1,
                representativePublicKey = publicKey,
            )
        val previous =
            Account(
                publicKey = publicKey,
                network = block.network,
                version = block.version,
                algorithm = block.algorithm,
                height = 0,
                balance = AttoAmount.MIN,
                lastTransactionHash = block.hash,
                lastTransactionTimestamp = timestamp,
                representativeAlgorithm = block.representativeAlgorithm,
                representativePublicKey = block.representativePublicKey,
            )
        val account = previous.copy(height = 1, balance = block.balance, persistedAt = timestamp, updatedAt = timestamp)
        val transaction = Transaction(block, AttoSignature(ByteArray(64)), AttoWork(ByteArray(8)))
        return AccountUpdated(TransactionSource.BOOTSTRAP, previous, account, transaction)
    }

    private fun received(previous: Account): AccountUpdated {
        val height = previous.height + 1
        val timestamp = previous.lastTransactionTimestamp.plusSeconds(1)
        val block =
            AttoReceiveBlock(
                version = previous.version,
                network = previous.network,
                algorithm = previous.algorithm,
                publicKey = previous.publicKey,
                height = height.toULong().toAttoHeight(),
                balance = AttoAmount(height.toULong() * 100UL),
                timestamp = timestamp.toAtto(),
                previous = previous.lastTransactionHash,
                sendHashAlgorithm = AttoAlgorithm.V1,
                sendHash = AttoHash(ByteArray(32) { height.toByte() }),
            )
        val account =
            previous.copy(
                height = height,
                balance = block.balance,
                lastTransactionHash = block.hash,
                lastTransactionTimestamp = timestamp,
                updatedAt = timestamp,
            )
        val transaction = Transaction(block, AttoSignature(ByteArray(64)), AttoWork(ByteArray(8)))
        return AccountUpdated(TransactionSource.ELECTION, previous, account, transaction)
    }
}
