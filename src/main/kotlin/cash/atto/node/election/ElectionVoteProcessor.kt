package cash.atto.node.election

import cash.atto.commons.AttoPublicKey
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteService
import cash.atto.node.vote.weight.WeightService
import cash.atto.protocol.AttoNode
import kotlinx.coroutines.sync.Mutex
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Service
class ElectionVoteProcessor(
    private val thisNode: AttoNode,
    private val voteService: VoteService,
    private val weightService: WeightService,
    transactionManager: ReactiveTransactionManager,
) {
    private val buffer = ConcurrentLinkedDeque<ElectionConsensusReached>()
    private val bufferDepth = AtomicInteger()
    private val flushMutex = Mutex()
    private val transactionalOperator = TransactionalOperator.create(transactionManager)

    @EventListener
    suspend fun process(event: ElectionConsensusReached) {
        buffer.addLast(event)
        bufferDepth.incrementAndGet()
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
        val events = drainBatch(size)

        try {
            if (events.isEmpty()) return 0

            val finalVotes = events.flatMap { it.votes }.filter { it.isFinal() }
            val latestTimestamps =
                events
                    .asSequence()
                    .flatMap { it.votes.asSequence() }
                    .latestTimestampsByPublicKey()

            transactionalOperator.executeAndAwait {
                weightService.updateLastVoteTimestamps(latestTimestamps)

                if (thisNode.isHistorical() && finalVotes.isNotEmpty()) {
                    voteService.saveAll(finalVotes)
                }
            }

            return events.size
        } catch (e: Exception) {
            requeue(events)
            throw RuntimeException("Error while processing votes for ${events.map { it.transaction.hash }}", e)
        }
    }

    private fun drainBatch(size: Int): List<ElectionConsensusReached> {
        val events = mutableListOf<ElectionConsensusReached>()

        for (i in 1..size) {
            val event = buffer.pollFirst() ?: break
            events += event
        }

        if (events.isNotEmpty()) {
            bufferDepth.addAndGet(-events.size)
        }

        return events
    }

    private fun requeue(events: List<ElectionConsensusReached>) {
        events.asReversed().forEach { buffer.addFirst(it) }
        bufferDepth.addAndGet(events.size)
    }

    private fun Sequence<Vote>.latestTimestampsByPublicKey(): Map<AttoPublicKey, Instant> {
        val timestamps = HashMap<AttoPublicKey, Instant>()
        forEach { vote ->
            timestamps.merge(vote.publicKey, vote.receivedAt) { current, candidate ->
                if (candidate > current) candidate else current
            }
        }
        return timestamps
    }
}
