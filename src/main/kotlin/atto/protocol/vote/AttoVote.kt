package atto.protocol.vote

import cash.atto.commons.*
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import java.time.Instant

data class AttoVoteSignature(
    val algorithm: AttoAlgorithm,
    val timestamp: Instant,
    val publicKey: AttoPublicKey,
    val signature: AttoSignature
) {

    companion object {
        val size = 105
        val finalTimestamp = Instant.ofEpochMilli(Long.MAX_VALUE)

        fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoVoteSignature? {
            if (byteBuffer.size < size) {
                return null
            }

            return AttoVoteSignature(
                algorithm = byteBuffer.getAlgorithm(),
                timestamp = byteBuffer.getInstant().toJavaInstant(),
                publicKey = byteBuffer.getPublicKey(),
                signature = byteBuffer.getSignature()
            )
        }
    }

    fun toByteBuffer(): AttoByteBuffer {
        return AttoByteBuffer(size)
            .add(algorithm)
            .add(timestamp.toKotlinInstant())
            .add(publicKey)
            .add(signature)
    }

    fun isFinal(): Boolean {
        return timestamp == finalTimestamp
    }
}

data class AttoVote(
    val type: VoteType = VoteType.UNIQUE,
    val hash: AttoHash,
    val signature: AttoVoteSignature,
) {
    companion object {
        val size = AttoVoteSignature.size + 33
    }

    fun isValid(): Boolean {
        if (type == VoteType.UNKNOWN) {
            return false
        }

        if (!signature.isFinal() && signature.timestamp > Instant.now()) {
            return false
        }

        val voteHash = AttoHash.hash(
            32,
            hash.value,
            byteArrayOf(AttoAlgorithm.V1.code.toByte()),
            signature.timestamp.toKotlinInstant().toByteArray()
        )
        return signature.signature.isValid(signature.publicKey, voteHash)
    }
}

/**
 * This class is to add flexibility in the future in case we need to change how votes works (i.e.: bundle votes)
 */
enum class VoteType(val code: UByte) {
    UNIQUE(0u),

    UNKNOWN(UByte.MAX_VALUE);

    companion object {
        private val map = entries.associateBy(VoteType::code)
        fun from(code: UByte): VoteType {
            return map.getOrDefault(code, UNKNOWN)
        }
    }
}