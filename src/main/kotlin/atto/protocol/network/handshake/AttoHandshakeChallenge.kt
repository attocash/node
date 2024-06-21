package atto.protocol.network.handshake

import atto.protocol.AttoMessage
import atto.protocol.AttoMessageType
import cash.atto.commons.AttoNetwork
import cash.atto.commons.toHex
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.security.SecureRandom

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AttoHandshakeChallenge(
    @ProtoNumber(0) val value: ByteArray,
) : AttoMessage {
    companion object {
        const val SIZE = 128 // Should never be 32

        val random = SecureRandom.getInstanceStrong()!!

        fun create(): AttoHandshakeChallenge {
            val challenge = ByteArray(SIZE)
            random.nextBytes(challenge)
            return AttoHandshakeChallenge(challenge)
        }
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.HANDSHAKE_CHALLENGE
    }

    override fun isValid(network: AttoNetwork): Boolean {
        return value.size == SIZE
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttoHandshakeChallenge

        if (!value.contentEquals(other.value)) return false

        return true
    }

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "AttoHandshakeChallenge(value=${value.toHex()})"
}
