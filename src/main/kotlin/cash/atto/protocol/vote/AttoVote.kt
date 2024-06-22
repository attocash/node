package cash.atto.protocol.vote

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AttoVote(
    @ProtoNumber(0) val algorithm: AttoAlgorithm,
    @ProtoNumber(1) val timestamp: Instant,
    @ProtoNumber(2) val publicKey: AttoPublicKey,
    @ProtoNumber(3) val signature: AttoSignature,
) {
    companion object {
        val finalTimestamp = Instant.fromEpochMilliseconds(Long.MAX_VALUE)
    }

    fun isFinal(): Boolean {
        return timestamp == finalTimestamp
    }

    fun isValid(blockHash: AttoHash): Boolean {
        if (timestamp < Clock.System.now().minus(60.seconds)) {
            return false
        }

        val voteHash =
            AttoHash.hashVote(
                blockHash,
                algorithm,
                timestamp,
            )
        return signature.isValid(publicKey, voteHash)
    }
}
