package cash.atto.node.election

import cash.atto.node.vote.VoteService
import cash.atto.protocol.AttoNode
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class FinalVoteRecorder(
    private val thisNode: AttoNode,
    private val voteService: VoteService,
) {
    @EventListener
    fun process(event: ElectionConsensusReached) {
        if (!thisNode.isHistorical()) {
            return
        }

        val finalVotes = event.votes.filter { it.isFinal() }
        if (finalVotes.isEmpty()) {
            return
        }

        voteService.enqueueAll(finalVotes)
    }
}
