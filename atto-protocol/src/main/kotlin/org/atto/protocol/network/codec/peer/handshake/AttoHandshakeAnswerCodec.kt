package org.atto.protocol.network.codec.peer.handshake

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.AttoMessageType
import org.atto.protocol.network.codec.AttoMessageCodec
import org.atto.protocol.network.codec.AttoNodeCodec
import org.atto.protocol.network.handshake.AttoHandshakeAnswer


class AttoHandshakeAnswerCodec(val nodeCodec: AttoNodeCodec) : AttoMessageCodec<AttoHandshakeAnswer> {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.HANDSHAKE_ANSWER
    }

    override fun targetClass(): Class<AttoHandshakeAnswer> {
        return AttoHandshakeAnswer::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoHandshakeAnswer? {
        if (byteBuffer.size < AttoHandshakeAnswer.size) {
            return null
        }

        val node = nodeCodec.fromByteBuffer(byteBuffer.slice(64)) ?: return null

        return AttoHandshakeAnswer(
            signature = byteBuffer.getSignature(0), // TODO: Move the signature to the last
            node = node
        )
    }

    override fun toByteBuffer(t: AttoHandshakeAnswer): AttoByteBuffer {
        return AttoByteBuffer(AttoHandshakeAnswer.size)
            .add(t.signature)
            .add(nodeCodec.toByteBuffer(t.node))
    }
}