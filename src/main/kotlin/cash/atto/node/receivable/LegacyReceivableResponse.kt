package cash.atto.node.receivable

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.AttoVersion
import cash.atto.node.LegacyAmountSerializer
import cash.atto.node.LegacyInstantSerializer
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.serialization.Serializable

@Serializable
data class LegacyReceivableResponse(
    val network: AttoNetwork,
    val hash: AttoHash,
    val version: AttoVersion,
    val algorithm: AttoAlgorithm,
    val publicKey: AttoPublicKey,
    @get:Schema(type = "integer", format = "int64", implementation = Long::class)
    @Serializable(with = LegacyInstantSerializer::class)
    val timestamp: AttoInstant,
    val receiverAlgorithm: AttoAlgorithm,
    val receiverPublicKey: AttoPublicKey,
    @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
    @Serializable(with = LegacyAmountSerializer::class)
    val amount: AttoAmount,
)

fun AttoReceivable.toLegacyResponse(): LegacyReceivableResponse =
    LegacyReceivableResponse(
        network = network,
        hash = hash,
        version = version,
        algorithm = address.algorithm,
        publicKey = address.publicKey,
        timestamp = timestamp,
        receiverAlgorithm = receiverAddress.algorithm,
        receiverPublicKey = receiverAddress.publicKey,
        amount = amount,
    )
