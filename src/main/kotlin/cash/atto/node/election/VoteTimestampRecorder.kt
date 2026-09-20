package cash.atto.node.election

import cash.atto.commons.AttoPublicKey
import cash.atto.node.DemandDrivenWorker
import cash.atto.node.vote.weight.WeightService
import jakarta.annotation.PreDestroy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service
class VoteTimestampRecorder(
    private val weightService: WeightService,
) {
    private val pendingTimestamps = ConcurrentHashMap<AttoPublicKey, Instant>()
    private val worker = DemandDrivenWorker("vote-timestamp-recorder", drain = ::drain)

    @EventListener
    suspend fun process(event: ElectionConsensusReached) {
        event.votes.forEach { vote ->
            pendingTimestamps.merge(vote.publicKey, vote.receivedAt) { current, candidate ->
                if (candidate > current) candidate else current
            }
        }
        if (event.votes.isNotEmpty()) {
            worker.request()
        }
    }

    @PreDestroy
    fun stop() {
        worker.cancel()
    }

    fun getPendingSize(): Int = pendingTimestamps.size

    private suspend fun drain() {
        while (pendingTimestamps.isNotEmpty()) {
            flushPending()
        }
    }

    private suspend fun flushPending(): Int {
        val timestamps = pendingTimestamps.toMap()
        if (timestamps.isEmpty()) return 0

        weightService.recordLastVoteTimestamps(timestamps)

        timestamps.forEach { (publicKey, timestamp) ->
            pendingTimestamps.remove(publicKey, timestamp)
        }

        return timestamps.size
    }
}
