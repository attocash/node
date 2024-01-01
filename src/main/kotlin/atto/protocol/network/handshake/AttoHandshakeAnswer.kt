package atto.protocol.network.handshake

import atto.protocol.AttoNode
import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType
import cash.atto.commons.AttoSignature

data class AttoHandshakeAnswer(
    val node: AttoNode,
    val signature: AttoSignature,
) :
    AttoMessage {
    companion object {
        val size = AttoNode.size + AttoSignature.SIZE
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
        return "HandshakeAnswer(node=$node, signature=$signature)"
    }

}