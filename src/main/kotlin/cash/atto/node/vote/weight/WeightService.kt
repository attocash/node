package cash.atto.node.vote.weight

import cash.atto.commons.AttoPublicKey
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class WeightService(
    private val weightRepository: WeightRepository,
) {
    @Transactional
    suspend fun refresh(): Flow<Weight> {
        weightRepository.upsertWeights()
        weightRepository.deleteStale()
        return weightRepository.findAll()
    }

    @Transactional
    suspend fun updateLastVoteTimestamps(timestamps: Map<AttoPublicKey, Instant>) {
        for ((publicKey, timestamp) in timestamps) {
            weightRepository.updateLastVoteTimestamp(publicKey, timestamp)
        }
    }
}
