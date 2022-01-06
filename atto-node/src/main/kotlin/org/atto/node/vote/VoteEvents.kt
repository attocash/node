package org.atto.node.vote

import org.atto.node.Event
import org.atto.protocol.vote.HashVote
import java.net.InetSocketAddress

abstract class HashVoteEvent(open val hashVote: HashVote) : Event<HashVote>(hashVote)

data class HashVoteValidated(override val hashVote: HashVote) : HashVoteEvent(hashVote)

enum class VoteRejectionReasons {
    INVALID_VOTE,
    INVALID_VOTING_WEIGHT
}

data class HashVoteRejected(
    val socketAddress: InetSocketAddress?,
    val reasons: VoteRejectionReasons,
    override val hashVote: HashVote
) :
    HashVoteEvent(hashVote) {
}