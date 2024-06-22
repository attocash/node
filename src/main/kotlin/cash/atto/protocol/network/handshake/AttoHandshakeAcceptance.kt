package cash.atto.protocol.network.handshake

import cash.atto.commons.AttoNetwork
import cash.atto.protocol.AttoMessage
import cash.atto.protocol.AttoMessageType
import kotlinx.serialization.Serializable

@Serializable
class AttoHandshakeAcceptance : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.HANDSHAKE_ACCEPTANCE

    override fun isValid(network: AttoNetwork) = true
}
