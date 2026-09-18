package cash.atto.node.vote

import cash.atto.commons.AttoHash
import cash.atto.node.CacheSupport
import cash.atto.node.account.AccountUpdated
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Component
class VoteCleaner(
    private val voteRepository: VoteRepository,
    private val clock: Clock,
) : CacheSupport {
    companion object {
        private const val BATCH_SIZE = 1_000
        private val STARTUP_CLEANUP_GRACE = Duration.ofMinutes(5)
    }

    private val buffer = ConcurrentLinkedDeque<AttoHash>()
    private val bufferDepth = AtomicInteger()
    private val flushMutex = Mutex()

    @EventListener
    fun process(event: AccountUpdated) {
        val staleBlockHash = event.previousAccount.lastTransactionHash
        if (staleBlockHash == event.updatedAccount.lastTransactionHash) {
            return
        }

        buffer.addLast(staleBlockHash)
        bufferDepth.incrementAndGet()
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun flush() {
        if (!flushMutex.tryLock()) {
            return
        }

        try {
            flushBatch(BATCH_SIZE)
        } finally {
            flushMutex.unlock()
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun deleteStaleVotesOnStartup() =
        runBlocking {
            flushMutex.withLock {
                voteRepository.deleteStale(clock.instant().minus(STARTUP_CLEANUP_GRACE))
            }
        }

    fun getBufferSize(): Int = bufferDepth.get()

    override fun clear() {
        buffer.clear()
        bufferDepth.set(0)
    }

    private suspend fun flushBatch(size: Int): Int {
        val blockHashes = drainBatch(size)
        if (blockHashes.isEmpty()) {
            return 0
        }

        try {
            voteRepository.deleteByBlockHashes(blockHashes.distinct())
            return blockHashes.size
        } catch (e: Exception) {
            requeue(blockHashes)
            throw e
        }
    }

    private fun drainBatch(size: Int): List<AttoHash> {
        val blockHashes = mutableListOf<AttoHash>()

        for (i in 1..size) {
            val blockHash = buffer.pollFirst() ?: break
            blockHashes += blockHash
        }

        bufferDepth.addAndGet(-blockHashes.size)
        return blockHashes
    }

    private fun requeue(blockHashes: List<AttoHash>) {
        blockHashes.asReversed().forEach(buffer::addFirst)
        bufferDepth.addAndGet(blockHashes.size)
    }
}
