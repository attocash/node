package cash.atto.node.receivable

import cash.atto.commons.*
import cash.atto.node.Event
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.time.Instant

data class Receivable(
    @Id
    val hash: AttoHash,
    val version: AttoVersion,
    val algorithm: AttoAlgorithm,
    val receiverAlgorithm: AttoAlgorithm,
    val receiverPublicKey: AttoPublicKey,
    val amount: AttoAmount,
    val persistedAt: Instant? = null,
) : Persistable<AttoHash> {
    override fun getId(): AttoHash = hash

    override fun isNew(): Boolean = persistedAt == null

    fun toAttoReceivable(): AttoReceivable =
        AttoReceivable(
            hash = hash,
            version = version,
            algorithm = algorithm,
            receiverAlgorithm = receiverAlgorithm,
            receiverPublicKey = receiverPublicKey,
            amount = amount,
        )
}

fun AttoReceivable.toReceivable(): Receivable =
    Receivable(
        hash = hash,
        version = version,
        algorithm = algorithm,
        receiverAlgorithm = receiverAlgorithm,
        receiverPublicKey = receiverPublicKey,
        amount = amount,
    )

data class ReceivableSaved(
    val receivable: Receivable,
) : Event

data class ReceivableDeleted(
    val receivable: Receivable,
) : Event
