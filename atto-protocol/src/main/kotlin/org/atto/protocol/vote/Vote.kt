package org.atto.protocol.vote

import org.atto.commons.*
import java.time.Instant

data class Vote(
    val timestamp: Instant,
    val publicKey: AttoPublicKey,
    val signature: AttoSignature
) {

    companion object {
        val size = 104
        val finalTimestamp = Instant.ofEpochMilli(Long.MAX_VALUE)

        fun fromByteBuffer(byteBuffer: AttoByteBuffer): Vote? {
            if (byteBuffer.size < size) {
                return null
            }

            return Vote(
                timestamp = byteBuffer.getInstant(),
                publicKey = byteBuffer.getPublicKey(),
                signature = byteBuffer.getSignature()
            )
        }
    }

    fun toByteBuffer(): AttoByteBuffer {
        return AttoByteBuffer(size)
            .add(timestamp)
            .add(publicKey)
            .add(signature)
    }

    fun isFinal(): Boolean {
        return timestamp == finalTimestamp
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Vote

        if (timestamp != other.timestamp) return false
        if (!publicKey.value.contentEquals(other.publicKey.value)) return false
        if (!signature.value.contentEquals(other.signature.value)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + signature.hashCode()
        return result
    }


}

data class HashVote(
    val type: VoteType = VoteType.UNIQUE,
    val hash: AttoHash,
    val vote: Vote,
    val receivedTimestamp: Instant
) {
    companion object {
        val size = Vote.size + 33
    }

    fun isValid(): Boolean {
        if (type == VoteType.UNKNOWN) {
            return false
        }

        if (!vote.isFinal() && vote.timestamp > receivedTimestamp) {
            return false
        }

        val voteHash = AttoHashes.hash(32, hash.value + vote.timestamp.toByteArray())
        return vote.signature.isValid(vote.publicKey, voteHash)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HashVote

        if (type != other.type) return false
        if (hash != other.hash) return false
        if (vote != other.vote) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + hash.hashCode()
        result = 31 * result + vote.hashCode()
        result = 31 * result + receivedTimestamp.hashCode()
        return result
    }
}

/**
 * This class is to add flexibility in the future in case we need to change how votes works (i.e.: bundle votes)
 */
enum class VoteType(val code: UByte) {
    UNIQUE(0u),

    UNKNOWN(UByte.MAX_VALUE);

    companion object {
        private val map = values().associateBy(VoteType::code)
        fun from(code: UByte): VoteType {
            return map.getOrDefault(code, UNKNOWN)
        }
    }
}