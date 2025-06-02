package cash.atto.node.vote

import kotlinx.coroutines.flow.toList
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class VoteService(
    private val voteRepository: VoteRepository,
) {
    suspend fun saveAll(votes: Collection<Vote>): List<Vote> = voteRepository.saveAll(votes).toList()

    @Scheduled(initialDelay = 1, fixedRate = 1, timeUnit = TimeUnit.HOURS)
    suspend fun removeOld() {
        voteRepository.deleteOld()
    }
}
