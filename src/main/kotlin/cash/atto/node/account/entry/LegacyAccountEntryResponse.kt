package cash.atto.node.account.entry

import cash.atto.commons.AttoAccountEntry
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoBlockType
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoPublicKey
import cash.atto.node.LegacyAmountSerializer
import cash.atto.node.LegacyHeightSerializer
import cash.atto.node.LegacyInstantSerializer
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.serialization.Serializable

@Serializable
data class LegacyAccountEntryResponse(
    val hash: AttoHash,
    val algorithm: AttoAlgorithm,
    val publicKey: AttoPublicKey,
    @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
    @Serializable(with = LegacyHeightSerializer::class)
    val height: AttoHeight,
    val blockType: AttoBlockType,
    val subjectAlgorithm: AttoAlgorithm,
    val subjectPublicKey: AttoPublicKey,
    @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
    @Serializable(with = LegacyAmountSerializer::class)
    val previousBalance: AttoAmount,
    @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
    @Serializable(with = LegacyAmountSerializer::class)
    val balance: AttoAmount,
    @get:Schema(type = "integer", format = "int64", implementation = Long::class)
    @Serializable(with = LegacyInstantSerializer::class)
    val timestamp: AttoInstant,
) {
    val address = AttoAddress(algorithm, publicKey)
    val subjectAddress = AttoAddress(subjectAlgorithm, subjectPublicKey)
}

fun AttoAccountEntry.toLegacyResponse(): LegacyAccountEntryResponse =
    LegacyAccountEntryResponse(
        hash = hash,
        algorithm = address.algorithm,
        publicKey = address.publicKey,
        height = height,
        blockType = blockType,
        subjectAlgorithm = subjectAddress.algorithm,
        subjectPublicKey = subjectAddress.publicKey,
        previousBalance = previousBalance,
        balance = balance,
        timestamp = timestamp,
    )
