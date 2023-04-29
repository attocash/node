package atto.node.receivable

import atto.commons.AttoAmount
import atto.commons.AttoHash
import atto.commons.AttoPublicKey
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.time.Instant

data class Receivable(
    @Id
    val hash: AttoHash,
    val receiverPublicKey: AttoPublicKey,
    val amount: AttoAmount,
    val persistedAt: Instant? = null,
) : Persistable<AttoHash> {
    override fun getId(): AttoHash {
        return hash
    }

    override fun isNew(): Boolean {
        return persistedAt == null
    }

}