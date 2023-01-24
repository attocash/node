package org.atto.protocol.vote

import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.AttoMessageType


data class AttoVoteResponse(val votes: List<AttoVote>) : AttoMessage {
    companion object {
        const val maxCount = 20L
        val maxSize = maxCount * AttoVote.size
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.VOTE_RESPONSE
    }

}

