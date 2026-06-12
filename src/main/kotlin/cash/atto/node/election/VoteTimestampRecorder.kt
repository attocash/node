package cash.atto.node.election

import cash.atto.commons.AttoPublicKey
import cash.atto.node.vote.weight.WeightService
import kotlinx.coroutines.sync.Mutex
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service
class VoteTimestampRecorder(
    private val weightService: WeightService,
) {
    private val pendingTimestamps = ConcurrentHashMap<AttoPublicKey, Instant>()
    private val flushMutex = Mutex()

    @EventListener
    suspend fun process(event: ElectionConsensusReached) {
        event.votes.forEach { vote ->
            pendingTimestamps.merge(vote.publicKey, vote.receivedAt) { current, candidate ->
                if (candidate > current) candidate else current
            }
        }
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun flush() {
        if (!flushMutex.tryLock()) {
            return
        }
        try {
            flushPending()
        } finally {
            flushMutex.unlock()
        }
    }

    fun getPendingSize(): Int = pendingTimestamps.size

    private suspend fun flushPending(): Int {
        val timestamps = pendingTimestamps.toMap()
        if (timestamps.isEmpty()) return 0

        try {
            weightService.recordLastVoteTimestamps(timestamps)

            timestamps.forEach { (publicKey, timestamp) ->
                pendingTimestamps.remove(publicKey, timestamp)
            }

            return timestamps.size
        } catch (e: Exception) {
            throw RuntimeException("Error while recording vote timestamps", e)
        }
    }
}
