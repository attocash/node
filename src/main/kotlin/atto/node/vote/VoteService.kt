package atto.node.vote

import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service

@Service
class VoteService(
    private val voteRepository: VoteRepository,
) {
    suspend fun saveAll(votes: Collection<Vote>): List<Vote> = voteRepository.saveAll(votes).toList()
}
