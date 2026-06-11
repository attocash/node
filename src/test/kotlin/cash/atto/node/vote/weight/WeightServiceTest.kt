package cash.atto.node.vote.weight

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoPublicKey
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
import java.time.Instant
import kotlin.random.Random

class WeightServiceTest {
    @Test
    fun `should refresh weights without deleting existing timestamps first`() =
        runTest {
            val repository = mockk<WeightRepository>()
            val weights = listOf(Weight(randomPublicKey(), AttoAmount(10UL), Instant.now()))
            val service = WeightService(repository)

            coEvery { repository.upsertWeights() } returns Unit
            coEvery { repository.deleteStale() } returns Unit
            every { repository.findAll() } returns weights.asFlow()

            val result = service.refresh().toList()

            assertEquals(weights, result)
            coVerifyOrder {
                repository.upsertWeights()
                repository.deleteStale()
            }
        }

    @Test
    fun `should update latest vote timestamps`() =
        runTest {
            val repository = mockk<WeightRepository>()
            val service = WeightService(repository)
            val firstPublicKey = randomPublicKey()
            val secondPublicKey = randomPublicKey()
            val firstTimestamp = Instant.now()
            val secondTimestamp = firstTimestamp.plusSeconds(1)

            coEvery { repository.updateLastVoteTimestamp(any(), any()) } returns Unit

            service.updateLastVoteTimestamps(
                mapOf(
                    firstPublicKey to firstTimestamp,
                    secondPublicKey to secondTimestamp,
                ),
            )

            coVerify(exactly = 1) { repository.updateLastVoteTimestamp(firstPublicKey, firstTimestamp) }
            coVerify(exactly = 1) { repository.updateLastVoteTimestamp(secondPublicKey, secondTimestamp) }
        }

    private fun randomPublicKey(): AttoPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
}
