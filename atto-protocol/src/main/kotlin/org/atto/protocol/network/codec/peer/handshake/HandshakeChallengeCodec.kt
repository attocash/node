package org.atto.protocol.network.codec.peer.handshake

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

    override fun fromByteArray(byteArray: ByteArray): HandshakeChallenge? {
        if (byteArray.size < HandshakeChallenge.size) {
            return null
        }

        return HandshakeChallenge(
            value = byteArray.sliceArray(0 until HandshakeChallenge.size)
        )
    }

    override fun toByteArray(t: HandshakeChallenge): ByteArray {
        return t.value
    }
}