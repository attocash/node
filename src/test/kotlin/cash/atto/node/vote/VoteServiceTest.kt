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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

internal class VoteServiceTest {
    @Test
    fun `should enqueue and flush votes`() =
        runTest {
            // given
            val repository = mockk<VoteRepository>()
            val staleVoteBlockService = mockk<StaleVoteBlockService>()
            val service = VoteService(repository, staleVoteBlockService, Clock.systemUTC())
            val vote = Vote.sample()

            service.enqueue(vote)
            coEvery { repository.insertIgnoreAll(listOf(vote)) } returns 1L
            coEvery { staleVoteBlockService.flushQueued(1_000) } returns emptyList()
            coEvery { repository.deleteStaleByBlockHashes(listOf(vote.blockHash)) } returns 0

            // when
            service.flush()

            // then
            assertEquals(0, service.getBufferSize())
            coVerify(exactly = 1) { repository.insertIgnoreAll(listOf(vote)) }
            coVerify(exactly = 1) { staleVoteBlockService.flushQueued(1_000) }
            coVerify(exactly = 1) { repository.deleteStaleByBlockHashes(listOf(vote.blockHash)) }
            coVerify(exactly = 0) { repository.deleteStale() }
            coVerify(exactly = 0) { staleVoteBlockService.reconcileOld(any()) }
        }

    @Test
    fun `should flush at most one thousand votes at a time`() =
        runTest {
            // given
            val repository = mockk<VoteRepository>()
            val staleVoteBlockService = mockk<StaleVoteBlockService>()
            val service = VoteService(repository, staleVoteBlockService, Clock.systemUTC())
            val votes = List(1_001) { Vote.sample() }
            val savedVotes = mutableListOf<List<Vote>>()

            service.enqueueAll(votes)
            coEvery { repository.insertIgnoreAll(any()) } coAnswers {
                savedVotes += firstArg<Collection<Vote>>().toList()
                firstArg<Collection<Vote>>().size.toLong()
            }
            coEvery { staleVoteBlockService.flushQueued(1_000) } returns emptyList()
            coEvery { repository.deleteStaleByBlockHashes(any()) } returns 0

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
            val staleVoteBlockService = mockk<StaleVoteBlockService>()
            val service = VoteService(repository, staleVoteBlockService, Clock.fixed(Instant.EPOCH, ZoneId.systemDefault()))
            val vote = Vote.sample()

            service.enqueue(vote)
            coEvery { repository.insertIgnoreAll(listOf(vote)) } returns 1L
            coEvery { staleVoteBlockService.flushQueued(1_000) } returns emptyList()
            coEvery { repository.deleteStale() } returns 1
            coEvery { repository.deleteStaleByBlockHashes(listOf(vote.blockHash)) } returns 0

            // when
            service.requestOldVoteRemoval()
            service.flush()

            // then
            coVerifyOrder {
                repository.insertIgnoreAll(listOf(vote))
                staleVoteBlockService.flushQueued(1_000)
                repository.deleteStale()
                repository.deleteStaleByBlockHashes(listOf(vote.blockHash))
            }
            coVerify(exactly = 0) { staleVoteBlockService.reconcileOld(any()) }
            coVerify(exactly = 0) { staleVoteBlockService.deleteUnusedOlderThan(any()) }
        }

    @Test
    fun `should clean stale votes after queued stale blocks are flushed`() =
        runTest {
            // given
            val repository = mockk<VoteRepository>()
            val staleVoteBlockService = mockk<StaleVoteBlockService>()
            val service = VoteService(repository, staleVoteBlockService, Clock.fixed(Instant.EPOCH, ZoneId.systemDefault()))
            val staleBlockHash = AttoHash(Random.nextBytes(ByteArray(32)))

            coEvery { staleVoteBlockService.flushQueued(1_000) } returns listOf(staleBlockHash)
            coEvery { repository.deleteStaleByBlockHashes(listOf(staleBlockHash)) } returns 1

            // when
            service.flush()

            // then
            coVerifyOrder {
                staleVoteBlockService.flushQueued(1_000)
                repository.deleteStaleByBlockHashes(listOf(staleBlockHash))
            }
            coVerify(exactly = 0) { repository.deleteStale() }
            coVerify(exactly = 0) { staleVoteBlockService.reconcileOld(any()) }
            coVerify(exactly = 0) { staleVoteBlockService.deleteUnusedOlderThan(any()) }
        }

    @Test
    fun `should retry requested stale vote cleanup after failure`() =
        runTest {
            // given
            val repository = mockk<VoteRepository>()
            val staleVoteBlockService = mockk<StaleVoteBlockService>()
            val service = VoteService(repository, staleVoteBlockService, Clock.fixed(Instant.EPOCH, ZoneId.systemDefault()))
            val vote = Vote.sample()
            val nextVote = Vote.sample()
            var deleteAttempts = 0

            service.enqueue(vote)
            coEvery { repository.insertIgnoreAll(any()) } returns 1L
            coEvery { staleVoteBlockService.flushQueued(1_000) } returns emptyList()
            coEvery { repository.deleteStaleByBlockHashes(any()) } returns 0
            coEvery { repository.deleteStale() } coAnswers {
                deleteAttempts++
                if (deleteAttempts == 1) {
                    throw IllegalStateException("deadlock")
                }
                1
            }

            // when
            service.requestOldVoteRemoval()
            val failure = runCatching { service.flush() }.exceptionOrNull()

            // then
            assertEquals("deadlock", failure?.message)
            assertEquals(0, service.getBufferSize())
            coVerifyOrder {
                repository.insertIgnoreAll(listOf(vote))
                staleVoteBlockService.flushQueued(1_000)
                repository.deleteStale()
            }
            coVerify(exactly = 0) { staleVoteBlockService.reconcileOld(any()) }
            coVerify(exactly = 0) { staleVoteBlockService.deleteUnusedOlderThan(any()) }

            // when
            service.enqueue(nextVote)
            service.flush()

            // then
            assertEquals(0, service.getBufferSize())
            assertEquals(2, deleteAttempts)
        }

    @Test
    fun `should reconcile old vote blocks once during startup`() =
        runTest {
            // given
            val repository = mockk<VoteRepository>()
            val staleVoteBlockService = mockk<StaleVoteBlockService>()
            val clock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())
            val service = VoteService(repository, staleVoteBlockService, clock)

            coEvery { staleVoteBlockService.reconcileOld(Instant.EPOCH.minus(Duration.ofMinutes(5))) } returns 3
            coEvery { staleVoteBlockService.deleteUnusedOlderThan(Instant.EPOCH.minus(Duration.ofDays(1))) } returns 0
            coEvery { staleVoteBlockService.flushQueued(1_000) } returns emptyList()
            coEvery { repository.deleteStale() } returns 3

            // when
            service.reconcileOldVoteBlocksOnStartup()
            service.flush()

            // then
            coVerify(exactly = 1) { staleVoteBlockService.reconcileOld(Instant.EPOCH.minus(Duration.ofMinutes(5))) }
            coVerify(exactly = 1) { staleVoteBlockService.deleteUnusedOlderThan(Instant.EPOCH.minus(Duration.ofDays(1))) }
            coVerifyOrder {
                staleVoteBlockService.reconcileOld(Instant.EPOCH.minus(Duration.ofMinutes(5)))
                staleVoteBlockService.deleteUnusedOlderThan(Instant.EPOCH.minus(Duration.ofDays(1)))
                staleVoteBlockService.flushQueued(1_000)
                repository.deleteStale()
            }
        }

    @Test
    fun `should not insert votes when buffer is empty`() =
        runTest {
            // given
            val repository = mockk<VoteRepository>()
            val staleVoteBlockService = mockk<StaleVoteBlockService>()
            val service = VoteService(repository, staleVoteBlockService, Clock.systemUTC())
            coEvery { staleVoteBlockService.flushQueued(1_000) } returns emptyList()

            // when
            service.flush()

            // then
            assertEquals(0, service.getBufferSize())
            coVerify(exactly = 0) { repository.insertIgnoreAll(any()) }
            coVerify(exactly = 1) { staleVoteBlockService.flushQueued(1_000) }
            coVerify(exactly = 0) { repository.deleteStale() }
            coVerify(exactly = 0) { repository.deleteStaleByBlockHashes(any()) }
            coVerify(exactly = 0) { staleVoteBlockService.reconcileOld(any()) }
        }

    @Test
    fun `should delete unused stale vote blocks older than the grace period`() =
        runTest {
            // Given
            val repository = mockk<VoteRepository>()
            val staleVoteBlockService = mockk<StaleVoteBlockService>()
            val clock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())
            val service = VoteService(repository, staleVoteBlockService, clock)
            coEvery { staleVoteBlockService.deleteUnusedOlderThan(Instant.EPOCH.minus(Duration.ofDays(1))) } returns 2

            // When
            service.deleteUnusedStaleVoteBlocks()

            // Then
            coVerify(exactly = 1) {
                staleVoteBlockService.deleteUnusedOlderThan(Instant.EPOCH.minus(Duration.ofDays(1)))
            }
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
