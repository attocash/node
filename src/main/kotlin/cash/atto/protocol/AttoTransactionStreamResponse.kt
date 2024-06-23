package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoTransaction
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("TRANSACTION_STREAM_RESPONSE")
data class AttoTransactionStreamResponse(
    @ProtoNumber(0) val transaction: AttoTransaction,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.TRANSACTION_STREAM_RESPONSE

    override fun isValid(network: AttoNetwork): Boolean = transaction.isValid(network)
}
