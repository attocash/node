package cash.atto.node.vote

import cash.atto.node.CacheSupport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Service
class VoteService(
    private val voteRepository: VoteRepository,
) : CacheSupport {
    companion object {
        private const val BATCH_SIZE = 1_000
    }

    private val logger = KotlinLogging.logger {}
    private val buffer = ConcurrentLinkedDeque<Vote>()
    private val bufferDepth = AtomicInteger()
    private val flushMutex = Mutex()
    private val oldVoteRemovalRequested = AtomicBoolean(false)

    fun enqueue(vote: Vote) {
        buffer.addLast(vote)
        bufferDepth.incrementAndGet()
    }

    fun enqueueAll(votes: Collection<Vote>) {
        if (votes.isEmpty()) {
            return
        }

        votes.forEach { buffer.addLast(it) }
        bufferDepth.addAndGet(votes.size)
    }

    override fun clear() {
        buffer.clear()
        bufferDepth.set(0)
        oldVoteRemovalRequested.set(false)
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun flush() {
        if (!flushMutex.tryLock()) {
            return
        }
        try {
            flushBatch(BATCH_SIZE)

            if (oldVoteRemovalRequested.compareAndSet(true, false)) {
                try {
                    voteRepository.deleteOld()
                } catch (e: Exception) {
                    oldVoteRemovalRequested.set(true)
                    logger.warn(e) { "Failed to remove old votes after saving votes" }
                }
            }
        } finally {
            flushMutex.unlock()
        }
    }

    fun getBufferSize(): Int = bufferDepth.get()

    private suspend fun saveAll(votes: Collection<Vote>): List<Vote> {
        val distinctVotes = votes.distinctBy { it.signature }

        if (distinctVotes.isEmpty()) {
            return distinctVotes
        }

        voteRepository.insertIgnoreAll(distinctVotes)

        return distinctVotes
    }

    @Scheduled(initialDelay = 1, fixedRate = 1, timeUnit = TimeUnit.HOURS)
    fun requestOldVoteRemoval() {
        oldVoteRemovalRequested.set(true)
    }

    private suspend fun flushBatch(size: Int): Int {
        val votes = drainBatch(size)
        if (votes.isEmpty()) {
            return 0
        }

        saveAll(votes)
        return votes.size
    }

    private fun drainBatch(size: Int): List<Vote> {
        val votes = mutableListOf<Vote>()

        for (i in 1..size) {
            val vote = buffer.pollFirst() ?: break
            votes += vote
        }

        if (votes.isNotEmpty()) {
            bufferDepth.addAndGet(-votes.size)
        }

        return votes
    }
}
