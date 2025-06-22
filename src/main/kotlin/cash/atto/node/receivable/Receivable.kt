package cash.atto.node.receivable

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.AttoVersion
import cash.atto.node.Event
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.time.Instant

data class Receivable(
    @Id
    val hash: AttoHash,
    val version: AttoVersion,
    val algorithm: AttoAlgorithm,
    val publicKey: AttoPublicKey,
    val timestamp: Instant,
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
            publicKey = publicKey,
            timestamp = timestamp.toKotlinInstant(),
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
        publicKey = publicKey,
        timestamp = timestamp.toJavaInstant(),
        receiverAlgorithm = receiverAlgorithm,
        receiverPublicKey = receiverPublicKey,
        amount = amount,
    )

data class ReceivableSaved(
    val receivable: Receivable,
    override val timestamp: Instant = Instant.now(),
) : Event
