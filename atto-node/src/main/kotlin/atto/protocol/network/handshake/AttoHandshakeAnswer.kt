package atto.protocol.network.handshake

import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType
import cash.atto.commons.AttoSignature

data class AttoHandshakeAnswer(
    val signature: AttoSignature,
    val node: atto.protocol.AttoNode
) :
    AttoMessage {
    companion object {
        val size = atto.protocol.AttoNode.size + AttoSignature.size
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.HANDSHAKE_ANSWER
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttoHandshakeAnswer

        if (signature != other.signature) return false
        if (node != other.node) return false

        return true
    }

    override fun hashCode(): Int {
        var result = signature.hashCode()
        result = 31 * result + node.hashCode()
        return result
    }

    override fun toString(): String {
        return "HandshakeAnswer(signature=$signature, node=$node)"
    }

}