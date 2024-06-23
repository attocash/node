package cash.atto.node.vote

import cash.atto.commons.*
import cash.atto.node.Event
import cash.atto.node.transaction.Transaction
import cash.atto.protocol.AttoVote
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.net.URI
import java.time.Instant

data class PublicKeyHash(
    val publicKey: AttoPublicKey,
    val hash: AttoHash,
)

data class Vote(
    val blockHash: AttoHash,
    val algorithm: AttoAlgorithm,
    val publicKey: AttoPublicKey,
    val timestamp: Instant,
    @Id
    val signature: AttoSignature,
    val weight: AttoAmount,
    val receivedAt: Instant = Instant.now(),
    val persistedAt: Instant? = null,
) : Persistable<AttoSignature> {
    companion object {
        fun from(
            weight: AttoAmount,
            hash: AttoHash,
            attoVote: AttoVote,
        ): Vote =
            Vote(
                blockHash = hash,
                algorithm = attoVote.algorithm,
                publicKey = attoVote.publicKey,
                timestamp = attoVote.timestamp.toJavaInstant(),
                signature = attoVote.signature,
                weight = weight,
            )
    }

    override fun getId(): AttoSignature = signature

    override fun isNew(): Boolean = true

    fun isFinal(): Boolean = AttoVote.finalTimestamp == timestamp.toKotlinInstant()

    fun toPublicKeyHash(): PublicKeyHash = PublicKeyHash(publicKey, blockHash)

    fun toAttoVote(): AttoVote =
        AttoVote(
            timestamp = timestamp.toKotlinInstant(),
            algorithm = algorithm,
            publicKey = publicKey,
            signature = signature,
        )
}

data class VoteReceived(
    val publicUri: URI,
    val vote: Vote,
) : Event

data class VoteValidated(
    val transaction: Transaction,
    val vote: Vote,
) : Event

enum class VoteDropReason {
    SUPERSEDED,
    NO_ELECTION,
    TRANSACTION_DROPPED,
}

data class VoteDropped(
    val vote: Vote,
    val reason: VoteDropReason,
) : Event

enum class VoteRejectionReason {
    INVALID_VOTING_WEIGHT,
}

data class VoteRejected(
    val reason: VoteRejectionReason,
    val vote: Vote,
) : Event
