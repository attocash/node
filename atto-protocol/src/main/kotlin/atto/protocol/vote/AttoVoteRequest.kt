package atto.protocol.vote

import atto.commons.AttoHash
import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType


data class AttoVoteRequest(val hash: AttoHash) : AttoMessage {
    companion object {
        val size = AttoHash.defaultSize
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.VOTE_REQUEST
    }

}

