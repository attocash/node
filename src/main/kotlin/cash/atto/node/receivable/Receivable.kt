package cash.atto.node.receivable

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.AttoVersion
import cash.atto.commons.toAtto
import cash.atto.commons.toJavaInstant
import cash.atto.node.Event
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.time.Instant

data class Receivable(
    @Id
    val hash: AttoHash,
    val network: AttoNetwork,
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
            network = network,
            hash = hash,
            version = version,
            address = AttoAddress(algorithm, publicKey),
            timestamp = timestamp.toAtto(),
            receiverAddress = AttoAddress(receiverAlgorithm, receiverPublicKey),
            amount = amount,
        )
}

fun AttoReceivable.toReceivable(): Receivable =
    Receivable(
        network = network,
        hash = hash,
        version = version,
        algorithm = address.algorithm,
        publicKey = address.publicKey,
        timestamp = timestamp.toJavaInstant(),
        receiverAlgorithm = receiverAddress.algorithm,
        receiverPublicKey = receiverAddress.publicKey,
        amount = amount,
    )

data class ReceivableSaved(
    val receivable: Receivable,
    override val timestamp: Instant = Instant.now(),
) : Event
