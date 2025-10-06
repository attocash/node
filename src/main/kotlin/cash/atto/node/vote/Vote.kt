package cash.atto.node.vote

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoSignedVote
import cash.atto.commons.AttoVersion
import cash.atto.commons.AttoVote
import cash.atto.commons.toAtto
import cash.atto.commons.toJavaInstant
import cash.atto.node.Event
import cash.atto.node.transaction.Transaction
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.net.URI
import java.time.Instant

data class PublicKeyHash(
    val publicKey: AttoPublicKey,
    val hash: AttoHash,
)

data class Vote(
    @Id
    val hash: AttoHash,
    val version: AttoVersion,
    val algorithm: AttoAlgorithm,
    val publicKey: AttoPublicKey,
    val blockAlgorithm: AttoAlgorithm,
    val blockHash: AttoHash,
    val timestamp: Instant,
    val signature: AttoSignature,
    val weight: AttoAmount,
    val receivedAt: Instant = Instant.now(),
    val persistedAt: Instant? = null,
) : Persistable<AttoSignature> {
    companion object {
        fun from(
            weight: AttoAmount,
            attoSignedVote: AttoSignedVote,
        ): Vote {
            val attoVote = attoSignedVote.vote
            return Vote(
                hash = attoSignedVote.hash,
                version = attoVote.version,
                algorithm = attoVote.algorithm,
                publicKey = attoVote.publicKey,
                blockAlgorithm = attoVote.blockAlgorithm,
                blockHash = attoVote.blockHash,
                timestamp = attoVote.timestamp.toJavaInstant(),
                signature = attoSignedVote.signature,
                weight = weight,
            )
        }
    }

    override fun getId(): AttoSignature = signature

    override fun isNew(): Boolean = true

    fun isFinal(): Boolean = AttoVote.finalTimestamp == timestamp.toAtto()

    fun toPublicKeyHash(): PublicKeyHash = PublicKeyHash(publicKey, blockHash)

    fun toAtto(): AttoSignedVote {
        val vote =
            AttoVote(
                version = version,
                algorithm = algorithm,
                publicKey = publicKey,
                blockAlgorithm = blockAlgorithm,
                blockHash = blockHash,
                timestamp = timestamp.toAtto(),
            )
        return AttoSignedVote(
            vote = vote,
            signature = signature,
        )
    }
}

data class VoteReceived(
    val publicUri: URI,
    val vote: Vote,
    override val timestamp: Instant = Instant.now(),
) : Event

data class VoteValidated(
    val transaction: Transaction,
    val vote: Vote,
    override val timestamp: Instant = Instant.now(),
) : Event

enum class VoteDropReason {
    SUPERSEDED,
    NO_ELECTION,
    TRANSACTION_DROPPED,
}

data class VoteDropped(
    val vote: Vote,
    val reason: VoteDropReason,
    override val timestamp: Instant = Instant.now(),
) : Event

enum class VoteRejectionReason {
    INVALID_VOTING_WEIGHT,
}

data class VoteRejected(
    val reason: VoteRejectionReason,
    val vote: Vote,
    override val timestamp: Instant = Instant.now(),
) : Event
