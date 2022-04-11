package org.atto.protocol.network.codec.peer.handshake

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.MessageType
import org.atto.protocol.network.codec.MessageCodec
import org.atto.protocol.network.codec.NodeCodec
import org.atto.protocol.network.handshake.HandshakeAnswer


class HandshakeAnswerCodec(val nodeCodec: NodeCodec) : MessageCodec<HandshakeAnswer> {

    override fun messageType(): MessageType {
        return MessageType.HANDSHAKE_ANSWER
    }

    override fun targetClass(): Class<HandshakeAnswer> {
        return HandshakeAnswer::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): HandshakeAnswer? {
        if (byteBuffer.size < HandshakeAnswer.size) {
            return null
        }

        val node = nodeCodec.fromByteBuffer(byteBuffer.slice(64)) ?: return null

        return HandshakeAnswer(
            signature = byteBuffer.getSignature(0),
            node = node
        )
    }

    override fun toByteBuffer(t: HandshakeAnswer): AttoByteBuffer {
        return AttoByteBuffer(HandshakeAnswer.size)
            .add(t.signature)
            .add(nodeCodec.toByteBuffer(t.node))
    }
}