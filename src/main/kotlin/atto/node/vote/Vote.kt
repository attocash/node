package atto.node.vote

import atto.node.Event
import atto.node.transaction.Transaction
import atto.protocol.vote.AttoVote
import atto.protocol.vote.AttoVoteSignature
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.net.InetSocketAddress
import java.time.Instant

data class PublicKeyHash(val publicKey: AttoPublicKey, val hash: AttoHash)

data class Vote(
    val hash: AttoHash,
    val publicKey: AttoPublicKey,
    val timestamp: Instant,
    @Id
    val signature: AttoSignature,
    val weight: AttoAmount,

    val receivedAt: Instant = Instant.now(),
    val persistedAt: Instant? = null,
) : Persistable<AttoSignature> {

    companion object {
        fun from(weight: AttoAmount, attoVote: AttoVote): Vote {
            return Vote(
                hash = attoVote.hash,
                publicKey = attoVote.signature.publicKey,
                timestamp = attoVote.signature.timestamp,
                signature = attoVote.signature.signature,
                weight = weight,
            )
        }
    }

    override fun getId(): AttoSignature {
        return signature
    }

    override fun isNew(): Boolean {
        return persistedAt == null
    }

    fun isFinal(): Boolean {
        return AttoVoteSignature.finalTimestamp == timestamp
    }

    fun toPublicKeyHash(): PublicKeyHash {
        return PublicKeyHash(publicKey, hash)
    }

    fun toAttoVote(): AttoVote {
        val voteSignature = AttoVoteSignature(
            timestamp = timestamp,
            publicKey = publicKey,
            signature = signature
        )

        return AttoVote(
            hash = hash,
            signature = voteSignature
        )
    }
}

data class VoteReceived(
    val socketAddress: InetSocketAddress,
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
}

data class VoteRejected(
    val reason: VoteRejectionReason,
    val vote: Vote
) : Event