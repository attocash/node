package org.atto.protocol.vote

import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.MessageType

data class VotePush(val hashVote: HashVote) : AttoMessage {
    companion object {
        val size = HashVote.size
    }

    override fun messageType(): MessageType {
        return MessageType.VOTE_PUSH
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VotePush

        if (hashVote != other.hashVote) return false

        return true
    }

    override fun hashCode(): Int {
        return hashVote.hashCode()
    }

    override fun toString(): String {
        return "VotePush(vote=$hashVote)"
    }
}

