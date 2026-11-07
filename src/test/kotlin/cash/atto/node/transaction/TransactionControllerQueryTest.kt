package cash.atto.node.transaction

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoTransaction
import cash.atto.commons.AttoWork
import cash.atto.commons.toAtto
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.account.AccountUpdated
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
class TransactionControllerQueryTest {
    private val publicKey = AttoPublicKey(ByteArray(32) { 1 })
    private val repository = mockk<TransactionRepository>()
    private val controller = TransactionController(repository)

    @Test
    fun `hash stream subscribes before lookup and retains a concurrent committed result`() =
        runTest {
            // Given
            val value = sample(2)
            val finishRead = CompletableDeferred<Unit>()
            lateinit var publication: Deferred<Unit>
            coEvery { repository.findById(value.hash) } coAnswers {
                publication = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { publish(value) }
                finishRead.await()
                null
            }
            val result = backgroundScope.async { controller.stream(value.hash).first() }

            // When
            runCurrent()

            // Then
            assertFalse(publication.isCompleted)
            assertFalse(result.isCompleted)

            // When
            finishRead.complete(Unit)
            runCurrent()

            // Then
            assertEquals(value.toAttoTransaction(), result.await())
            publication.await()
        }

    @Test
    fun `height stream orders snapshot and overlapping live updates and completes its range`() =
        runTest {
            // Given
            val first = sample(2)
            val second = sample(3, first.hash)
            val third = sample(4, second.hash)
            val finishRead = CompletableDeferred<Unit>()
            lateinit var overlap: Deferred<Unit>
            coEvery { repository.findAsc(any(), any(), any()) } coAnswers {
                overlap = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { publish(second) }
                flow {
                    emit(first)
                    emit(second)
                    finishRead.await()
                }
            }
            val values = mutableListOf<AttoTransaction>()
            val collection =
                backgroundScope.launch {
                    controller.stream(publicKey, 2UL.toAttoHeight(), 4UL.toAttoHeight()).toList(values)
                }

            // When
            runCurrent()

            // Then
            assertFalse(overlap.isCompleted)
            assertEquals(listOf(first, second).map { it.toAttoTransaction() }, values)

            // When
            finishRead.complete(Unit)
            runCurrent()
            overlap.await()
            publish(third)
            runCurrent()

            // Then
            collection.join()
            assertTrue(collection.isCompleted)
            assertEquals(listOf(first, second, third).map { it.toAttoTransaction() }, values)
        }

    @Test
    fun `cancelling a blocked snapshot cancels the query and releases the live publisher`() =
        runTest {
            // Given
            val value = sample(2)
            val cancelled = CompletableDeferred<Unit>()
            coEvery { repository.findAsc(any(), any(), any()) } returns
                flow {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            val collection =
                backgroundScope.launch {
                    controller.stream(publicKey, 2UL.toAttoHeight(), 3UL.toAttoHeight()).toList()
                }
            runCurrent()
            val publication = backgroundScope.async { publish(value) }
            runCurrent()
            assertFalse(publication.isCompleted)

            // When
            collection.cancelAndJoin()
            runCurrent()

            // Then
            publication.await()
            assertTrue(cancelled.isCompleted)
        }

    private suspend fun publish(value: Transaction) {
        controller.process(AccountUpdated(TransactionSource.BOOTSTRAP, mockk(), mockk(), value))
    }

    private fun sample(
        height: Int,
        previous: AttoHash = AttoHash(ByteArray(32)),
    ): Transaction =
        Transaction(
            AttoReceiveBlock(
                version = 0U.toAttoVersion(),
                network = AttoNetwork.LOCAL,
                algorithm = AttoAlgorithm.V1,
                publicKey = publicKey,
                height = height.toULong().toAttoHeight(),
                balance = AttoAmount(height.toULong()),
                timestamp = Instant.EPOCH.plusSeconds(height.toLong()).toAtto(),
                previous = previous,
                sendHashAlgorithm = AttoAlgorithm.V1,
                sendHash = AttoHash(ByteArray(32) { height.toByte() }),
            ),
            AttoSignature(ByteArray(64)),
            AttoWork(ByteArray(8)),
        )
}
