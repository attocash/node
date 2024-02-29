package atto.node.vote

import atto.node.Event
import atto.node.transaction.Transaction
import atto.protocol.vote.AttoVote
import cash.atto.commons.*
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.net.URI
import java.time.Instant

data class PublicKeyHash(val publicKey: AttoPublicKey, val hash: AttoHash)

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
        fun from(weight: AttoAmount, hash: AttoHash, attoVote: AttoVote): Vote {
            return Vote(
                blockHash = hash,
                algorithm = attoVote.algorithm,
                publicKey = attoVote.publicKey,
                timestamp = attoVote.timestamp.toJavaInstant(),
                signature = attoVote.signature,
                weight = weight,
            )
        }
    }

    override fun getId(): AttoSignature {
        return signature
    }

    override fun isNew(): Boolean {
        return true
    }

    fun isFinal(): Boolean {
        return AttoVote.finalTimestamp == timestamp.toKotlinInstant()
    }

    fun toPublicKeyHash(): PublicKeyHash {
        return PublicKeyHash(publicKey, blockHash)
    }

    fun toAttoVote(): AttoVote {
        return AttoVote(
            timestamp = timestamp.toKotlinInstant(),
            algorithm = algorithm,
            publicKey = publicKey,
            signature = signature
        )
    }
}

data class VoteReceived(
    val publicUri: URI,
    val vote: Vote
) : Event

data class VoteValidated(
    val transaction: Transaction,
    val vote: Vote
) : Event

enum class VoteDropReason {
    SUPERSEDED, NO_ELECTION, TRANSACTION_DROPPED
}

data class VoteDropped(
    val vote: Vote,
    val reason: VoteDropReason
) : Event

enum class VoteRejectionReason {
    INVALID_VOTING_WEIGHT,
    OLD_VOTE,
}

data class VoteRejected(
    val reason: VoteRejectionReason,
    val vote: Vote
) : Event