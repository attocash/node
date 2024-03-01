package atto.protocol.network.handshake

import atto.protocol.AttoMessage
import atto.protocol.AttoMessageType
import cash.atto.commons.AttoNetwork
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class AttoHandshakeAcceptance : AttoMessage {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.HANDSHAKE_ACCEPTANCE
    }

    override fun isValid(network: AttoNetwork) = true

}