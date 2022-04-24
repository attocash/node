package org.atto.protocol.vote

import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.AttoMessageType

data class AttoVotePush(val vote: AttoVote) : AttoMessage {
    companion object {
        val size = AttoVote.size
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.VOTE_PUSH
    }

}

