package cash.atto.node.vote

import cash.atto.node.account.AccountUpdated
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class StaleVoteBlockRecorder(
    private val staleVoteBlockService: StaleVoteBlockService,
) {
    @EventListener
    fun process(event: AccountUpdated) {
        val staleBlockHash = event.previousAccount.lastTransactionHash
        if (staleBlockHash == event.updatedAccount.lastTransactionHash) {
            return
        }

        staleVoteBlockService.record(staleBlockHash)
    }
}
