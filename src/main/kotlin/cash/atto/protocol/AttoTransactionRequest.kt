package cash.atto.protocol

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHashAsByteArraySerializer
import cash.atto.commons.AttoNetwork
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("AttoTransactionRequest")
data class AttoTransactionRequest(
    @ProtoNumber(1)
    @Serializable(with = AttoHashAsByteArraySerializer::class)
    val hash: AttoHash,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.TRANSACTION_REQUEST

    override fun isValid(network: AttoNetwork): Boolean = true
}
