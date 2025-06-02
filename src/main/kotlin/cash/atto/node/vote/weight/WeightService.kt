package cash.atto.node.vote.weight

import kotlinx.coroutines.flow.Flow
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

@Service
class WeightService(
    private val weightRepository: WeightRepository,
) {
    @Transactional
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    suspend fun refresh(): Flow<Weight> {
        weightRepository.deleteAll()
        weightRepository.refreshWeights()
        return weightRepository.findAll()
    }
}
