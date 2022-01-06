package org.atto.node.vote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class VoteService(private val scope: CoroutineScope, private val voteRepository: HashVoteRepository) {

    @EventListener
    fun process(hashVoteValidated: HashVoteValidated) {
        val hashVote = hashVoteValidated.hashVote
        if (!hashVote.vote.isFinal()) {
            return
        }
        scope.launch {
            voteRepository.save(hashVote)
        }
    }
}