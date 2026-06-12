package cash.atto.node.vote.weight

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoPublicKey
import org.springframework.data.annotation.Id
import java.time.Instant

data class Weight(
    @Id
    val representativePublicKey: AttoPublicKey,
    val weight: AttoAmount,
    val lastVoteTimestamp: Instant = NEVER_VOTED_AT,
) {
    companion object {
        val NEVER_VOTED_AT: Instant = Instant.EPOCH
    }
}
