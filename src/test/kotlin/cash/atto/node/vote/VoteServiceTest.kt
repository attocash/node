package cash.atto.node.vote

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.toAttoVersion
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

internal class VoteServiceTest {
    @Test
    fun `should enqueue and flush votes`() =
        runTest {
            // given
            val repository = mockk<VoteRepository>()
            val service = VoteService(repository)
            val vote = Vote.sample()

            service.enqueue(vote)
            coEvery { repository.insertIgnoreAll(listOf(vote)) } returns 1L

            // when
            service.flush()

            // then
            assertEquals(0, service.getBufferSize())
            coVerify(exactly = 1) { repository.insertIgnoreAll(listOf(vote)) }
        }

    @Test
    fun `should flush at most one thousand votes at a time`() =
        runTest {
            // given
            val repository = mockk<VoteRepository>()
            val service = VoteService(repository)
            val votes = List(1_001) { Vote.sample() }
            val savedVotes = mutableListOf<List<Vote>>()

            service.enqueueAll(votes)
            coEvery { repository.insertIgnoreAll(any()) } coAnswers {
                savedVotes += firstArg<Collection<Vote>>().toList()
                firstArg<Collection<Vote>>().size.toLong()
            }

            // when
            service.flush()

            // then
            assertEquals(1, service.getBufferSize())
            assertEquals(listOf(votes.take(1_000)), savedVotes)

            // when
            service.flush()

            // then
            assertEquals(0, service.getBufferSize())
            assertEquals(listOf(votes.take(1_000), votes.drop(1_000)), savedVotes)
        }

    @Test
    fun `should not insert votes when buffer is empty`() =
        runTest {
            // given
            val repository = mockk<VoteRepository>()
            val service = VoteService(repository)

            // when
            service.flush()

            // then
            assertEquals(0, service.getBufferSize())
            coVerify(exactly = 0) { repository.insertIgnoreAll(any()) }
        }

    private fun Vote.Companion.sample(): Vote =
        Vote(
            hash = AttoHash(Random.nextBytes(ByteArray(32))),
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = AttoHash(Random.nextBytes(ByteArray(32))),
            timestamp = Instant.now(),
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            weight = AttoAmount(1UL),
        )
}
