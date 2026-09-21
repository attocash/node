package cash.atto.node.vote.weight

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toBigInteger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.ReactiveTransactionManager
import reactor.core.publisher.Mono
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class WeightServiceTest {
    @Test
    fun `should calculate and replace weights in public key order`() =
        runTest {
            // Given
            val firstPublicKey = publicKey(1)
            val secondPublicKey = publicKey(2)
            val firstWeight = Weight(firstPublicKey, AttoAmount(1UL), Instant.ofEpochSecond(1))
            val secondWeight = Weight(secondPublicKey, AttoAmount(2UL), Instant.ofEpochSecond(2))
            val repository = mockk<WeightRepository>()
            val service = service(repository)

            every { repository.findCalculatedWeights() } returns listOf(secondWeight, firstWeight).asFlow()
            coEvery { repository.upsert(any(), any(), any()) } returns Unit
            coEvery { repository.deleteAllExcept(any()) } returns Unit

            // When
            val result = service.refresh().toList()

            // Then
            assertEquals(listOf(secondWeight, firstWeight), result)
            coVerifyOrder {
                repository.upsert(firstPublicKey, 1UL.toBigInteger(), firstWeight.lastVoteTimestamp.toUtcDateTime())
                repository.upsert(secondPublicKey, 2UL.toBigInteger(), secondWeight.lastVoteTimestamp.toUtcDateTime())
                repository.deleteAllExcept(listOf(firstPublicKey, secondPublicKey))
            }
        }

    @Test
    fun `should delete all stored weights when the calculated snapshot is empty`() =
        runTest {
            // Given
            val repository = mockk<WeightRepository>()
            val service = service(repository)
            every { repository.findCalculatedWeights() } returns emptyList<Weight>().asFlow()
            coEvery { repository.deleteAll() } returns Unit

            // When
            service.refresh().toList()

            // Then
            coVerify(exactly = 1) { repository.deleteAll() }
            coVerify(exactly = 0) { repository.deleteAllExcept(any()) }
        }

    @Test
    fun `should update latest vote timestamps`() =
        runTest {
            // Given
            val repository = mockk<WeightRepository>()
            val service = service(repository)
            val firstPublicKey = publicKey(1)
            val secondPublicKey = publicKey(2)
            val firstTimestamp = Instant.ofEpochSecond(1)
            val secondTimestamp = Instant.ofEpochSecond(2)

            coEvery { repository.recordLastVoteTimestamp(any(), any()) } returns Unit

            // When
            service.recordLastVoteTimestamps(
                linkedMapOf(
                    secondPublicKey to secondTimestamp,
                    firstPublicKey to firstTimestamp,
                ),
            )

            // Then
            coVerifyOrder {
                repository.recordLastVoteTimestamp(firstPublicKey, firstTimestamp.toUtcDateTime())
                repository.recordLastVoteTimestamp(secondPublicKey, secondTimestamp.toUtcDateTime())
            }
        }

    private fun service(repository: WeightRepository): WeightService {
        val transaction = mockk<ReactiveTransaction>(relaxed = true)
        val transactionManager = mockk<ReactiveTransactionManager>()
        every { transactionManager.getReactiveTransaction(any()) } returns Mono.just(transaction)
        every { transactionManager.commit(transaction) } returns Mono.empty()
        every { transactionManager.rollback(transaction) } returns Mono.empty()
        return WeightService(repository, transactionManager)
    }

    private fun publicKey(lastByte: Byte): AttoPublicKey = AttoPublicKey(ByteArray(32).also { it[it.lastIndex] = lastByte })

    private fun Instant.toUtcDateTime(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}
