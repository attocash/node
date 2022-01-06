package org.atto.protocol.network.handshake

import org.atto.commons.AttoSignature
import org.atto.protocol.Node
import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.MessageType

data class HandshakeAnswer(
    val signature: AttoSignature,
    val node: Node
) :
    AttoMessage {
    companion object {
        val size = Node.size + AttoSignature.size
    }

    override fun messageType(): MessageType {
        return MessageType.HANDSHAKE_ANSWER
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HandshakeAnswer

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