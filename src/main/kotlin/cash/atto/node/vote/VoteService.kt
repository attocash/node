package cash.atto.node.vote

import cash.atto.node.CacheSupport
import cash.atto.node.DemandDrivenWorker
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

@Service
class VoteService(
    private val voteRepository: VoteRepository,
) : CacheSupport {
    companion object {
        private const val BATCH_SIZE = 1_000
    }

    private val buffer = ConcurrentLinkedDeque<Vote>()
    private val bufferDepth = AtomicInteger()
    private val worker = DemandDrivenWorker("vote-service", drain = ::drain)

    fun enqueue(vote: Vote) {
        buffer.addLast(vote)
        bufferDepth.incrementAndGet()
        worker.request()
    }

    fun enqueueAll(votes: Collection<Vote>) {
        if (votes.isEmpty()) {
            return
        }

        votes.forEach { buffer.addLast(it) }
        bufferDepth.addAndGet(votes.size)
        worker.request()
    }

    override fun clear() {
        buffer.clear()
        bufferDepth.set(0)
    }

    @PreDestroy
    fun stop() {
        worker.cancel()
    }

    fun getBufferSize(): Int = bufferDepth.get()

    private suspend fun drain() {
        while (bufferDepth.get() > 0) {
            flushBatch(BATCH_SIZE)
        }
    }

    private suspend fun saveAll(votes: Collection<Vote>): List<Vote> {
        val distinctVotes = votes.distinctBy { it.signature }

        if (distinctVotes.isEmpty()) {
            return distinctVotes
        }

        voteRepository.insertIgnoreAll(distinctVotes)

        return distinctVotes
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
