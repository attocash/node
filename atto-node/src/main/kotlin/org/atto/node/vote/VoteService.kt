package org.atto.node.vote

import org.springframework.stereotype.Service

@Service
class VoteService(private val voteRepository: VoteRepository) {

    suspend fun saveAll(votes: Collection<Vote>) {
        voteRepository.saveAll(votes)
    }
}