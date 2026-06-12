package cash.atto.node.election

import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteService
import cash.atto.protocol.AttoNode
import kotlinx.coroutines.sync.Mutex
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Service
class FinalVoteRecorder(
    private val thisNode: AttoNode,
    private val voteService: VoteService,
) {
    private val buffer = ConcurrentLinkedDeque<Vote>()
    private val bufferDepth = AtomicInteger()
    private val flushMutex = Mutex()

    @EventListener
    suspend fun process(event: ElectionConsensusReached) {
        if (!thisNode.isHistorical()) {
            return
        }

        val finalVotes = event.votes.filter { it.isFinal() }
        if (finalVotes.isEmpty()) {
            return
        }

        finalVotes.forEach { buffer.addLast(it) }
        bufferDepth.addAndGet(finalVotes.size)
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun flush() {
        if (!flushMutex.tryLock()) {
            return
        }
        try {
            flushBatch(1_000)
        } finally {
            flushMutex.unlock()
        }
    }

    fun getBufferSize(): Int = bufferDepth.get()

    private suspend fun flushBatch(size: Int): Int {
        val finalVotes = drainBatch(size)
        if (finalVotes.isEmpty()) return 0

        try {
            voteService.saveAll(finalVotes)

            return finalVotes.size
        } catch (e: Exception) {
            requeue(finalVotes)
            throw RuntimeException("Error while processing final votes", e)
        }
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

    private fun requeue(votes: List<Vote>) {
        votes.asReversed().forEach { buffer.addFirst(it) }
        bufferDepth.addAndGet(votes.size)
    }
}
