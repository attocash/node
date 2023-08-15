package atto.protocol.vote

import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType

data class AttoVotePush(val vote: AttoVote) : AttoMessage {
    companion object {
        val size = AttoVote.size
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.VOTE_PUSH
    }

}

