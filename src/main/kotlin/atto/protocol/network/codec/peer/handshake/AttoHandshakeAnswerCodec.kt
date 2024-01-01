package atto.protocol.network.codec.peer.handshake

import atto.protocol.network.AttoMessageType
import atto.protocol.network.codec.AttoMessageCodec
import atto.protocol.network.codec.AttoNodeCodec
import atto.protocol.network.handshake.AttoHandshakeAnswer
import cash.atto.commons.AttoByteBuffer


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

        val node = nodeCodec.fromByteBuffer(byteBuffer) ?: return null

        return AttoHandshakeAnswer(
            node = node,
            signature = byteBuffer.getSignature(),
        )
    }

    override fun toByteBuffer(t: AttoHandshakeAnswer): AttoByteBuffer {
        return AttoByteBuffer(AttoHandshakeAnswer.size + t.node.features.size)
            .add(nodeCodec.toByteBuffer(t.node))
            .add(t.signature)
    }
}