package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import cash.atto.protocol.serializer.URISerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.net.URI

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("AttoKeepAlive")
data class AttoKeepAlive(
    @ProtoNumber(0)
    val neighbour:
        @Serializable(with = URISerializer::class)
        URI? = null,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.KEEP_ALIVE

    override fun isValid(network: AttoNetwork): Boolean = neighbour?.path == null
}
