package org.atto.protocol.network.codec.peer.handshake

import org.atto.commons.AttoSignature
import org.atto.protocol.network.MessageType
import org.atto.protocol.network.codec.MessageCodec
import org.atto.protocol.network.codec.NodeCodec
import org.atto.protocol.network.handshake.HandshakeAnswer
import java.nio.ByteBuffer


class HandshakeAnswerCodec(val nodeCodec: NodeCodec) : MessageCodec<HandshakeAnswer> {

    override fun messageType(): MessageType {
        return MessageType.HANDSHAKE_ANSWER
    }

    override fun targetClass(): Class<HandshakeAnswer> {
        return HandshakeAnswer::class.java
    }

    override fun fromByteArray(byteArray: ByteArray): HandshakeAnswer? {
        if (byteArray.size < HandshakeAnswer.size) {
            return null
        }

        val node = nodeCodec.fromByteArray(byteArray.sliceArray(64 until byteArray.size)) ?: return null

        return HandshakeAnswer(
            signature = AttoSignature(byteArray.sliceArray(0 until 64)),
            node = node
        )
    }

    override fun toByteArray(t: HandshakeAnswer): ByteArray {
        return ByteBuffer.allocate(HandshakeAnswer.size)
            .put(t.signature.value)
            .put(nodeCodec.toByteArray(t.node))
            .array()
    }
}