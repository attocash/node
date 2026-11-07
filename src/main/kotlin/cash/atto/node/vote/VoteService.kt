package cash.atto.node.vote

import cash.atto.node.CacheSupport
import cash.atto.node.DemandDrivenWorker
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service

@Service
class VoteService(
    private val voteRepository: VoteRepository,
) : CacheSupport {
    companion object {
        private const val BATCH_SIZE = 1_000
    }

    private val bufferLock = Any()
    private val buffer = ArrayDeque<Vote>()
    private var clearGeneration = 0L
    private val worker = DemandDrivenWorker("vote-service", drain = ::drain)

    fun enqueue(vote: Vote) {
        synchronized(bufferLock) {
            buffer.addLast(vote)
        }
        worker.request()
    }

    fun enqueueAll(votes: Collection<Vote>) {
        if (votes.isEmpty()) {
            return
        }

        synchronized(bufferLock) {
            buffer.addAll(votes)
        }
        worker.request()
    }

    override fun clear() {
        synchronized(bufferLock) {
            buffer.clear()
            clearGeneration++
        }
    }

    @PreDestroy
    fun stop() {
        worker.cancel()
    }

    fun getBufferSize(): Int = synchronized(bufferLock) { buffer.size }

    private suspend fun drain() {
        while (true) {
            val batch = drainBatch() ?: return
            try {
                voteRepository.insertIgnoreAll(batch.votes.distinctBy { it.signature })
            } catch (exception: Exception) {
                synchronized(bufferLock) {
                    if (batch.clearGeneration == clearGeneration) {
                        batch.votes.asReversed().forEach(buffer::addFirst)
                    }
                }
                throw exception
            }
        }
    }

    private fun drainBatch(): PendingBatch? =
        synchronized(bufferLock) {
            if (buffer.isEmpty()) {
                return@synchronized null
            }
            val votes = List(minOf(BATCH_SIZE, buffer.size)) { buffer.removeFirst() }
            PendingBatch(votes, clearGeneration)
        }

    private data class PendingBatch(
        val votes: List<Vote>,
        val clearGeneration: Long,
    )
}
