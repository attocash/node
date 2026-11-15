package cash.atto.node.transaction

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoBlock
import cash.atto.commons.AttoBlockType
import cash.atto.commons.AttoChangeBlock
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoOpenBlock
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoTransaction
import cash.atto.commons.AttoVersion
import cash.atto.commons.AttoWork
import cash.atto.node.LegacyAmountSerializer
import cash.atto.node.LegacyHeightSerializer
import cash.atto.node.LegacyInstantSerializer
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class LegacyTransactionResponse(
    val block: LegacyBlock,
    val signature: AttoSignature,
    val work: AttoWork,
)

@Serializable
@Schema(
    name = "LegacyBlock",
    discriminatorProperty = "type",
    discriminatorMapping = [
        DiscriminatorMapping(value = "OPEN", schema = LegacyBlock.Open::class),
        DiscriminatorMapping(value = "RECEIVE", schema = LegacyBlock.Receive::class),
        DiscriminatorMapping(value = "SEND", schema = LegacyBlock.Send::class),
        DiscriminatorMapping(value = "CHANGE", schema = LegacyBlock.Change::class),
    ],
)
sealed interface LegacyBlock {
    @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = ["OPEN", "RECEIVE", "SEND", "CHANGE"])
    val type: AttoBlockType

    val network: AttoNetwork
    val version: AttoVersion
    val algorithm: AttoAlgorithm
    val publicKey: AttoPublicKey
    val address: AttoAddress

    @get:Schema(description = "Height of the block (unsigned 64-bit)")
    val height: AttoHeight
    val balance: AttoAmount
    val timestamp: AttoInstant

    @Serializable
    @SerialName("OPEN")
    @Schema(name = "LegacyOpenBlock")
    data class Open(
        override val network: AttoNetwork,
        override val version: AttoVersion,
        override val algorithm: AttoAlgorithm,
        override val publicKey: AttoPublicKey,
        @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
        @Serializable(with = LegacyAmountSerializer::class)
        override val balance: AttoAmount,
        @get:Schema(type = "integer", format = "int64", implementation = Long::class)
        @Serializable(with = LegacyInstantSerializer::class)
        override val timestamp: AttoInstant,
        @get:Schema(description = "Algorithm of the send block")
        val sendHashAlgorithm: AttoAlgorithm,
        @get:Schema(description = "Hash of the send block (hex)")
        val sendHash: AttoHash,
        @get:Schema(description = "Algorithm of the representative")
        val representativeAlgorithm: AttoAlgorithm,
        val representativePublicKey: AttoPublicKey,
        @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
        @Serializable(with = LegacyHeightSerializer::class)
        override val height: AttoHeight,
        override val address: AttoAddress,
        @get:Schema(description = "Address of the representative")
        val representativeAddress: AttoAddress,
    ) : LegacyBlock {
        @Transient
        @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = ["OPEN"])
        override val type = AttoBlockType.OPEN
    }

    @Serializable
    @SerialName("RECEIVE")
    @Schema(name = "LegacyReceiveBlock")
    data class Receive(
        override val network: AttoNetwork,
        override val version: AttoVersion,
        override val algorithm: AttoAlgorithm,
        override val publicKey: AttoPublicKey,
        @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
        @Serializable(with = LegacyHeightSerializer::class)
        override val height: AttoHeight,
        @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
        @Serializable(with = LegacyAmountSerializer::class)
        override val balance: AttoAmount,
        @get:Schema(type = "integer", format = "int64", implementation = Long::class)
        @Serializable(with = LegacyInstantSerializer::class)
        override val timestamp: AttoInstant,
        @get:Schema(description = "Hash of the previous block (hex)")
        val previous: AttoHash,
        @get:Schema(description = "Algorithm of the send block")
        val sendHashAlgorithm: AttoAlgorithm,
        @get:Schema(description = "Hash of the send block (hex)")
        val sendHash: AttoHash,
        override val address: AttoAddress,
    ) : LegacyBlock {
        @Transient
        @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = ["RECEIVE"])
        override val type = AttoBlockType.RECEIVE
    }

    @Serializable
    @SerialName("SEND")
    @Schema(name = "LegacySendBlock")
    data class Send(
        override val network: AttoNetwork,
        override val version: AttoVersion,
        override val algorithm: AttoAlgorithm,
        override val publicKey: AttoPublicKey,
        @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
        @Serializable(with = LegacyHeightSerializer::class)
        override val height: AttoHeight,
        @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
        @Serializable(with = LegacyAmountSerializer::class)
        override val balance: AttoAmount,
        @get:Schema(type = "integer", format = "int64", implementation = Long::class)
        @Serializable(with = LegacyInstantSerializer::class)
        override val timestamp: AttoInstant,
        @get:Schema(description = "Hash of the previous block (hex)")
        val previous: AttoHash,
        @get:Schema(description = "Algorithm of the receiver")
        val receiverAlgorithm: AttoAlgorithm,
        val receiverPublicKey: AttoPublicKey,
        @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
        @Serializable(with = LegacyAmountSerializer::class)
        val amount: AttoAmount,
        override val address: AttoAddress,
        @get:Schema(description = "Address of the receiver")
        val receiverAddress: AttoAddress,
    ) : LegacyBlock {
        @Transient
        @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = ["SEND"])
        override val type = AttoBlockType.SEND
    }

    @Serializable
    @SerialName("CHANGE")
    @Schema(name = "LegacyChangeBlock")
    data class Change(
        override val network: AttoNetwork,
        override val version: AttoVersion,
        override val algorithm: AttoAlgorithm,
        override val publicKey: AttoPublicKey,
        @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
        @Serializable(with = LegacyHeightSerializer::class)
        override val height: AttoHeight,
        @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
        @Serializable(with = LegacyAmountSerializer::class)
        override val balance: AttoAmount,
        @get:Schema(type = "integer", format = "int64", implementation = Long::class)
        @Serializable(with = LegacyInstantSerializer::class)
        override val timestamp: AttoInstant,
        @get:Schema(description = "Hash of the previous block (hex)")
        val previous: AttoHash,
        @get:Schema(description = "Algorithm of the representative")
        val representativeAlgorithm: AttoAlgorithm,
        val representativePublicKey: AttoPublicKey,
        override val address: AttoAddress,
        @get:Schema(description = "Address of the representative")
        val representativeAddress: AttoAddress,
    ) : LegacyBlock {
        @Transient
        @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = ["CHANGE"])
        override val type = AttoBlockType.CHANGE
    }
}

fun AttoTransaction.toLegacyResponse(): LegacyTransactionResponse = LegacyTransactionResponse(block.toLegacyResponse(), signature, work)

internal fun AttoBlock.toLegacyResponse(): LegacyBlock =
    when (this) {
        is AttoOpenBlock -> {
            LegacyBlock.Open(
                network = network,
                version = version,
                algorithm = address.algorithm,
                publicKey = address.publicKey,
                address = address,
                height = height,
                balance = balance,
                timestamp = timestamp,
                sendHashAlgorithm = sendHashAlgorithm,
                sendHash = sendHash,
                representativeAlgorithm = representativeAddress.algorithm,
                representativePublicKey = representativeAddress.publicKey,
                representativeAddress = representativeAddress,
            )
        }

        is AttoReceiveBlock -> {
            LegacyBlock.Receive(
                network = network,
                version = version,
                algorithm = address.algorithm,
                publicKey = address.publicKey,
                address = address,
                height = height,
                balance = balance,
                timestamp = timestamp,
                previous = previous,
                sendHashAlgorithm = sendHashAlgorithm,
                sendHash = sendHash,
            )
        }

        is AttoSendBlock -> {
            LegacyBlock.Send(
                network = network,
                version = version,
                algorithm = address.algorithm,
                publicKey = address.publicKey,
                address = address,
                height = height,
                balance = balance,
                timestamp = timestamp,
                previous = previous,
                receiverAlgorithm = receiverAddress.algorithm,
                receiverPublicKey = receiverAddress.publicKey,
                receiverAddress = receiverAddress,
                amount = amount,
            )
        }

        is AttoChangeBlock -> {
            LegacyBlock.Change(
                network = network,
                version = version,
                algorithm = address.algorithm,
                publicKey = address.publicKey,
                address = address,
                height = height,
                balance = balance,
                timestamp = timestamp,
                previous = previous,
                representativeAlgorithm = representativeAddress.algorithm,
                representativePublicKey = representativeAddress.publicKey,
                representativeAddress = representativeAddress,
            )
        }
    }
