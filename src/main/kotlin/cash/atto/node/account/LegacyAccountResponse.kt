package cash.atto.node.account

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoVersion
import cash.atto.node.LegacyAmountSerializer
import cash.atto.node.LegacyHeightSerializer
import cash.atto.node.LegacyInstantSerializer
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.serialization.Serializable

@Serializable
data class LegacyAccountResponse(
    val publicKey: AttoPublicKey,
    val network: AttoNetwork,
    val version: AttoVersion,
    val algorithm: AttoAlgorithm,
    @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
    @Serializable(with = LegacyHeightSerializer::class)
    val height: AttoHeight,
    @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
    @Serializable(with = LegacyAmountSerializer::class)
    val balance: AttoAmount,
    val lastTransactionHash: AttoHash,
    @get:Schema(type = "integer", format = "int64", implementation = Long::class)
    @Serializable(with = LegacyInstantSerializer::class)
    val lastTransactionTimestamp: AttoInstant,
    val representativeAlgorithm: AttoAlgorithm,
    val representativePublicKey: AttoPublicKey,
)

fun AttoAccount.toLegacyResponse(): LegacyAccountResponse =
    LegacyAccountResponse(
        publicKey = address.publicKey,
        network = network,
        version = version,
        algorithm = address.algorithm,
        height = height,
        balance = balance,
        lastTransactionHash = lastTransactionHash,
        lastTransactionTimestamp = lastTransactionTimestamp,
        representativeAlgorithm = representativeAddress.algorithm,
        representativePublicKey = representativeAddress.publicKey,
    )
