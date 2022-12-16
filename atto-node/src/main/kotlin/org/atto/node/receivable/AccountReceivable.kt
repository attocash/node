package org.atto.node.receivable

import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.time.Instant

data class AccountReceivable(
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