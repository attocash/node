package org.atto.protocol.network.handshake

import org.atto.commons.checkLength
import org.atto.commons.toHex
import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.AttoMessageType
import kotlin.random.Random


data class AttoHandshakeChallenge(val value: ByteArray) : AttoMessage {
    companion object {
        // never use 32
        const val size = 16

        fun create(): AttoHandshakeChallenge {
            return AttoHandshakeChallenge(Random.Default.nextBytes(ByteArray(16)))
        }
    }

    init {
        value.checkLength(size)
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.HANDSHAKE_CHALLENGE
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttoHandshakeChallenge

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

