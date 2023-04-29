package atto.protocol.vote

import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType


data class AttoVoteResponse(val votes: List<AttoVote>) : AttoMessage {
    companion object {
        const val maxCount = 20L
        val maxSize = maxCount * AttoVote.size
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.VOTE_RESPONSE
    }

}

