package atto.protocol.network.handshake

import atto.protocol.AttoMessage
import atto.protocol.AttoMessageType
import atto.protocol.AttoNode
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoSignature
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AttoHandshakeAnswer(
    @ProtoNumber(0)
    val node: AttoNode,
    @ProtoNumber(1)
    @Contextual
    val signature: AttoSignature,
) :
    AttoMessage {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.HANDSHAKE_ANSWER
    }

    override fun isValid(network: AttoNetwork) = true

}