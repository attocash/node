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
    @ProtoNumber(0)
    @Serializable(with = AttoPublicKeyAsByteArraySerializer::class)
    val publicKey: AttoPublicKey,
    @ProtoNumber(1) val startHeight: AttoHeight,
    @ProtoNumber(2) val endHeight: AttoHeight,
) : AttoMessage {
    companion object {
        const val MAX_TRANSACTIONS = 1000UL
    }

    override fun messageType(): AttoMessageType = AttoMessageType.TRANSACTION_STREAM_REQUEST

    override fun isValid(network: AttoNetwork): Boolean =
        startHeight < endHeight && endHeight - startHeight <= MAX_TRANSACTIONS.toAttoHeight()
}
