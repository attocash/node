package org.atto.protocol.network.codec.peer.handshake

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.MessageType
import org.atto.protocol.network.codec.MessageCodec
import org.atto.protocol.network.handshake.HandshakeChallenge

class HandshakeChallengeCodec : MessageCodec<HandshakeChallenge> {

    override fun messageType(): MessageType {
        return MessageType.HANDSHAKE_CHALLENGE
    }

    override fun targetClass(): Class<HandshakeChallenge> {
        return HandshakeChallenge::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): HandshakeChallenge? {
        if (byteBuffer.size < HandshakeChallenge.size) {
            return null
        }

        return HandshakeChallenge(
            value = byteBuffer.getByteArray(0, HandshakeChallenge.size)
        )
    }

    override fun toByteBuffer(t: HandshakeChallenge): AttoByteBuffer {
        return AttoByteBuffer(HandshakeChallenge.size).add(t.value)
    }
}