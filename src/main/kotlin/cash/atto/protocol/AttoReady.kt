package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("AttoReady")
data object AttoReady : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.READY

    override fun isValid(network: AttoNetwork): Boolean = true
}
