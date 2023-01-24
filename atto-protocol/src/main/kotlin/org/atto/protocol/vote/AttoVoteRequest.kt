package org.atto.protocol.vote

import org.atto.commons.AttoHash
import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.AttoMessageType


data class AttoVoteRequest(val hash: AttoHash) : AttoMessage {
    companion object {
        val size = AttoHash.defaultSize
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.VOTE_REQUEST
    }

}

