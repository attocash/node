package atto.protocol.network.peer

import atto.protocol.AttoMessage
import atto.protocol.AttoMessageType
import atto.protocol.serializer.InetSocketAddressSerializer
import cash.atto.commons.AttoNetwork
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.net.InetSocketAddress

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AttoKeepAlive(
    @ProtoNumber(0)
    val neighbour: @Serializable(with = InetSocketAddressSerializer::class) InetSocketAddress
) : AttoMessage {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.KEEP_ALIVE
    }

    override fun isValid(network: AttoNetwork): Boolean = true
}