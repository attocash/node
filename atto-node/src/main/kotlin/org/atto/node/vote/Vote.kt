package org.atto.node.vote

import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.commons.AttoSignature
import org.atto.node.Event
import org.atto.node.transaction.Transaction
import org.atto.protocol.vote.AttoVote
import org.atto.protocol.vote.AttoVoteSignature
import org.springframework.data.annotation.Id
import java.net.InetSocketAddress
import java.time.Instant

data class PublicKeyHash(val publicKey: AttoPublicKey, val hash: AttoHash)

data class Vote(
    val hash: AttoHash,
    val publicKey: AttoPublicKey,
    val timestamp: Instant,
    @Id
    val signature: AttoSignature,
    val receivedTimestamp: Instant,
    val weight: AttoAmount,
) {

    companion object {
        fun from(weight: AttoAmount, attoVote: AttoVote): Vote {
            return Vote(
                hash = attoVote.hash,
                publicKey = attoVote.signature.publicKey,
                timestamp = attoVote.signature.timestamp,
                signature = attoVote.signature.signature,
                receivedTimestamp = Instant.now(),
                weight = weight,
            )
        }
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

interface VoteEvent : Event<Vote>

data class VoteReceived(
    val socketAddress: InetSocketAddress,
    override val payload: Vote
) : VoteEvent

data class VoteValidated(
    val transaction: Transaction,
    override val payload: Vote
) : VoteEvent

data class VoteDropped(
    val transaction: Transaction,
    override val payload: Vote
) : VoteEvent

enum class VoteRejectionReason {
    INVALID_VOTING_WEIGHT,
}

data class VoteRejected(
    val reason: VoteRejectionReason,
    override val payload: Vote
) : VoteEvent