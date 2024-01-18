package atto.node.receivable

import atto.node.Event
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
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


data class ReceivableSaved(
    val receivable: Receivable,
) : Event

data class ReceivableDeleted(
    val receivable: Receivable,
) : Event