package cash.atto.node.vote

import cash.atto.commons.AttoHash
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

class StaleVoteBlockServiceTest {
    @Test
    fun `records stale block hash in queue`() =
        runTest {
            // given
            val blockHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val repository = mockk<StaleVoteBlockRepository>()
            val service = StaleVoteBlockService(repository)

            // when
            service.record(blockHash)

            // then
            assertEquals(1, service.getQueueSize())
            coVerify(exactly = 0) { repository.insertIgnoreAll(any()) }
        }

    @Test
    fun `flushes queued stale block hashes through repository`() =
        runTest {
            // given
            val blockHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val duplicateBlockHash = blockHash
            val nextBlockHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val repository = mockk<StaleVoteBlockRepository>()
            val service = StaleVoteBlockService(repository)

            service.record(blockHash)
            service.record(duplicateBlockHash)
            service.record(nextBlockHash)
            coEvery { repository.insertIgnoreAll(listOf(blockHash, nextBlockHash)) } returns 2L

            // when
            val flushed = service.flushQueued(1_000)

            // then
            assertEquals(2, flushed)
            assertEquals(0, service.getQueueSize())
            coVerify(exactly = 1) { repository.insertIgnoreAll(listOf(blockHash, nextBlockHash)) }
        }

    @Test
    fun `flushes at most requested stale block hashes`() =
        runTest {
            // given
            val firstBlockHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val secondBlockHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val repository = mockk<StaleVoteBlockRepository>()
            val service = StaleVoteBlockService(repository)

            service.record(firstBlockHash)
            service.record(secondBlockHash)
            coEvery { repository.insertIgnoreAll(listOf(firstBlockHash)) } returns 1L

            // when
            val flushed = service.flushQueued(1)

            // then
            assertEquals(1, flushed)
            assertEquals(1, service.getQueueSize())
            coVerify(exactly = 1) { repository.insertIgnoreAll(listOf(firstBlockHash)) }
        }

    @Test
    fun `drops stale block hashes when flush fails`() =
        runTest {
            // given
            val blockHash = AttoHash(Random.nextBytes(ByteArray(32)))
            val repository = mockk<StaleVoteBlockRepository>()
            val service = StaleVoteBlockService(repository)

            service.record(blockHash)
            coEvery { repository.insertIgnoreAll(listOf(blockHash)) } throws IllegalStateException("deadlock")

            // when
            val failedFlush = service.flushQueued(1_000)

            // then
            assertEquals(0, failedFlush)
            assertEquals(0, service.getQueueSize())
            coVerify(exactly = 1) { repository.insertIgnoreAll(listOf(blockHash)) }
        }

    @Test
    fun `reconciles old stale block hashes through repository`() =
        runTest {
            // given
            val receivedBefore = Instant.EPOCH
            val repository = mockk<StaleVoteBlockRepository>()
            val service = StaleVoteBlockService(repository)

            coEvery { repository.reconcileOld(receivedBefore) } returns 3

            // when
            val reconciled = service.reconcileOld(receivedBefore)

            // then
            assertEquals(3, reconciled)
            coVerify(exactly = 1) { repository.reconcileOld(receivedBefore) }
        }

    @Test
    fun `deletes unused stale block hashes through repository`() =
        runTest {
            // given
            val createdBefore = Instant.EPOCH
            val repository = mockk<StaleVoteBlockRepository>()
            val service = StaleVoteBlockService(repository)

            coEvery { repository.deleteUnusedOlderThan(createdBefore) } returns 2

            // when
            val deleted = service.deleteUnusedOlderThan(createdBefore)

            // then
            assertEquals(2, deleted)
            coVerify(exactly = 1) { repository.deleteUnusedOlderThan(createdBefore) }
        }
}
