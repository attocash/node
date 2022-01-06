package org.atto.protocol.network.handshake

import org.atto.commons.checkLength
import org.atto.commons.toHex
import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.MessageType
import kotlin.random.Random


data class HandshakeChallenge(val value: ByteArray) : AttoMessage {
    companion object {
        // never use 32
        const val size = 16

        fun create(): HandshakeChallenge {
            return HandshakeChallenge(Random.Default.nextBytes(ByteArray(16)))
        }
    }

    init {
        value.checkLength(size)
    }

    override fun messageType(): MessageType {
        return MessageType.HANDSHAKE_CHALLENGE
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HandshakeChallenge

        if (!value.contentEquals(other.value)) return false

        return true
    }

    override fun hashCode(): Int {
        return value.contentHashCode()
    }

    override fun toString(): String {
        return "HandshakeChallenge(value=${value.toHex()})"
    }
}

