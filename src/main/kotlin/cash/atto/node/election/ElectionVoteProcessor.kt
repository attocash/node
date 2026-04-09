package cash.atto.node.election

import cash.atto.commons.AttoPublicKey
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteService
import cash.atto.node.vote.weight.WeightService
import cash.atto.protocol.AttoNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.TimeUnit

@Service
class ElectionVoteProcessor(
    private val thisNode: AttoNode,
    private val voteService: VoteService,
    private val weightService: WeightService,
) {
    private val buffer = Channel<ElectionConsensusReached>(Channel.UNLIMITED)
    private val mutex = Mutex()

    @EventListener
    suspend fun process(event: ElectionConsensusReached) {
        buffer.send(event)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun flush() {
        if (mutex.isLocked) {
            return
        }
        mutex.withLock {
            while (!buffer.isEmpty) {
                flushBatch(1_000)
            }
        }
    }

    private suspend fun flushBatch(size: Int) {
        val finalVotes = mutableListOf<Vote>()
        val latestTimestamps = HashMap<AttoPublicKey, Instant>()

        while (finalVotes.size < size) {
            val event = buffer.tryReceive().getOrNull() ?: break

            for (vote in event.votes) {
                if (thisNode.isHistorical() && vote.isFinal()) {
                    finalVotes.add(vote)
                }
                latestTimestamps.merge(vote.publicKey, vote.receivedAt) { old, new ->
                    if (new > old) new else old
                }
            }
        }

        weightService.updateLastVoteTimestamps(latestTimestamps)

        if (thisNode.isHistorical() && finalVotes.isNotEmpty()) {
            voteService.saveAll(finalVotes)
        }
    }
}
