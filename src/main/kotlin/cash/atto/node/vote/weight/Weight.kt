package cash.atto.node.vote.weight

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoPublicKey
import org.springframework.data.annotation.Id

data class Weight(
    @Id
    val representativePublicKey: AttoPublicKey,
    val weight: AttoAmount,
)
