package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoSignature
import cash.atto.commons.serialiazers.AttoSignatureAsByteArraySerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("AttoHandshakeAnswer")
data class AttoHandshakeAnswer(
    @ProtoNumber(0)
    val node: AttoNode,
    @ProtoNumber(1)
    @Serializable(with = AttoSignatureAsByteArraySerializer::class)
    val signature: AttoSignature,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.HANDSHAKE_ANSWER

    override fun isValid(network: AttoNetwork) = true
}
