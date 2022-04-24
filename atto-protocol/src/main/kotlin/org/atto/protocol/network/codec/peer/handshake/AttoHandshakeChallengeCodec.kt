package org.atto.protocol.network.codec.peer.handshake

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.AttoMessageType
import org.atto.protocol.network.codec.AttoMessageCodec
import org.atto.protocol.network.handshake.AttoHandshakeChallenge

class AttoHandshakeChallengeCodec : AttoMessageCodec<AttoHandshakeChallenge> {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.HANDSHAKE_CHALLENGE
    }

    override fun targetClass(): Class<AttoHandshakeChallenge> {
        return AttoHandshakeChallenge::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoHandshakeChallenge? {
        if (byteBuffer.size < AttoHandshakeChallenge.size) {
            return null
        }

        return AttoHandshakeChallenge(
            value = byteBuffer.getByteArray(0, AttoHandshakeChallenge.size)
        )
    }

    override fun toByteBuffer(t: AttoHandshakeChallenge): AttoByteBuffer {
        return AttoByteBuffer(AttoHandshakeChallenge.size).add(t.value)
    }
}