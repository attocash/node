package cash.atto.node.vote

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.toAttoVersion
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
            coVerify(exactly = 0) { repository.deleteOld() }
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
    fun `should remove old votes after flushed votes when removal is requested`() =
        runTest {
            // given
            val repository = mockk<VoteRepository>()
            val service = VoteService(repository)
            val vote = Vote.sample()

            service.enqueue(vote)
            coEvery { repository.insertIgnoreAll(listOf(vote)) } returns 1L
            coEvery { repository.deleteOld() } returns 1

            // when
            service.requestOldVoteRemoval()
            service.flush()

            // then
            coVerifyOrder {
                repository.insertIgnoreAll(listOf(vote))
                repository.deleteOld()
            }
        }

    @Test
    fun `should keep flushed votes successful when old vote removal fails`() =
        runTest {
            // given
            val repository = mockk<VoteRepository>()
            val service = VoteService(repository)
            val vote = Vote.sample()
            val nextVote = Vote.sample()
            var deleteAttempts = 0

            service.enqueue(vote)
            coEvery { repository.insertIgnoreAll(any()) } returns 1L
            coEvery { repository.deleteOld() } coAnswers {
                deleteAttempts++
                if (deleteAttempts == 1) {
                    throw IllegalStateException("deadlock")
                }
                1
            }

            // when
            service.requestOldVoteRemoval()
            service.flush()

            // then
            assertEquals(0, service.getBufferSize())
            coVerifyOrder {
                repository.insertIgnoreAll(listOf(vote))
                repository.deleteOld()
            }

            // when
            service.enqueue(nextVote)
            service.flush()

            // then
            assertEquals(0, service.getBufferSize())
            assertEquals(2, deleteAttempts)
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
            coVerify(exactly = 0) { repository.deleteOld() }
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
