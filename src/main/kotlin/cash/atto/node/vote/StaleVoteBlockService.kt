package cash.atto.node.vote

import cash.atto.commons.AttoHash
import cash.atto.node.CacheSupport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

@Service
class StaleVoteBlockService(
    private val staleVoteBlockRepository: StaleVoteBlockRepository,
) : CacheSupport {
    private val buffer = ConcurrentLinkedDeque<AttoHash>()
    private val bufferDepth = AtomicInteger()
    private val flushMutex = Mutex()
    private val logger = KotlinLogging.logger {}

    fun record(blockHash: AttoHash) {
        buffer.addLast(blockHash)
        bufferDepth.incrementAndGet()
    }

    suspend fun flushQueued(limit: Int): Int {
        if (!flushMutex.tryLock()) {
            return 0
        }

        return try {
            val blockHashes = drainBatch(limit)
            if (blockHashes.isEmpty()) {
                return 0
            }

            staleVoteBlockRepository.insertIgnoreAll(blockHashes)
            blockHashes.size
        } catch (e: Exception) {
            logger.warn(e) { "Failed to flush stale vote blocks" }
            0
        } finally {
            flushMutex.unlock()
        }
    }

    suspend fun reconcileOld(receivedBefore: Instant): Int = staleVoteBlockRepository.reconcileOld(receivedBefore)

    suspend fun deleteUnusedOlderThan(createdBefore: Instant): Int = staleVoteBlockRepository.deleteUnusedOlderThan(createdBefore)

    fun getQueueSize(): Int = bufferDepth.get()

    override fun clear() {
        buffer.clear()
        bufferDepth.set(0)
    }

    private fun drainBatch(limit: Int): List<AttoHash> {
        val blockHashes = mutableListOf<AttoHash>()

        for (i in 1..limit) {
            val blockHash = buffer.pollFirst() ?: break
            blockHashes += blockHash
        }

        if (blockHashes.isNotEmpty()) {
            bufferDepth.addAndGet(-blockHashes.size)
        }

        return blockHashes.distinct()
    }
}
