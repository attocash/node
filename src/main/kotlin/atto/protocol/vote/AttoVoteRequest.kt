package atto.protocol.vote

import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType
import cash.atto.commons.AttoHash


data class AttoVoteRequest(val hash: AttoHash) : AttoMessage {
    companion object {
        val size = 32
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.VOTE_REQUEST
    }

}

