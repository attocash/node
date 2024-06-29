package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("AttoHandshakeAcceptance")
class AttoHandshakeAcceptance : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.HANDSHAKE_ACCEPTANCE

    override fun isValid(network: AttoNetwork) = true
}
