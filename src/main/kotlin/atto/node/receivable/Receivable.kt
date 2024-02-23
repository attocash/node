package atto.node.receivable

import atto.node.Event
import cash.atto.commons.*
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.time.Instant

data class Receivable(
    @Id
    val hash: AttoHash,
    val version: UShort,
    val algorithm: AttoAlgorithm,
    val receiverAlgorithm: AttoAlgorithm,
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

    fun toAttoReceivable(): AttoReceivable {
        return AttoReceivable(
            hash = hash,
            version = version,
            algorithm = algorithm,
            receiverAlgorithm = receiverAlgorithm,
            receiverPublicKey = receiverPublicKey,
            amount = amount,
        )
    }
}

fun AttoReceivable.toReceivable(): Receivable {
    return Receivable(
        hash = hash,
        version = version,
        algorithm = algorithm,
        receiverAlgorithm = receiverAlgorithm,
        receiverPublicKey = receiverPublicKey,
        amount = amount,
    )
}


data class ReceivableSaved(
    val receivable: Receivable,
) : Event

data class ReceivableDeleted(
    val receivable: Receivable,
) : Event