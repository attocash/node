package cash.atto.protocol

import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.serialiazer.AttoPublicKeyAsByteArraySerializer
import cash.atto.commons.toAttoHeight
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("AttoTransactionStreamRequest")
data class AttoTransactionStreamRequest(
    @ProtoNumber(1)
    @Serializable(with = AttoPublicKeyAsByteArraySerializer::class)
    val publicKey: AttoPublicKey,
    @ProtoNumber(2) val startHeight: AttoHeight,
    @ProtoNumber(3) val endHeight: AttoHeight,
) : AttoMessage {
    companion object {
        const val MAX_TRANSACTIONS = 1000UL
    }

    init {
        require(startHeight <= endHeight) { "End height must be greater than or equal to start height" }

        val count = endHeight.value - startHeight.value + 1UL
        require(MAX_TRANSACTIONS >= count) {
            "The number of transactions must not exceed the maximum limit of $MAX_TRANSACTIONS. Requested $count"
        }
    }

    override fun messageType(): AttoMessageType = AttoMessageType.TRANSACTION_STREAM_REQUEST

    override fun isValid(network: AttoNetwork): Boolean =
        startHeight < endHeight && endHeight - startHeight <= MAX_TRANSACTIONS.toAttoHeight()
}
