package atto.protocol.vote

import atto.protocol.AttoMessage
import atto.protocol.AttoMessageType
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber


@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AttoVoteResponse(
    @ProtoNumber(0) val blockHash: AttoHash,
    @ProtoNumber(1) val votes: List<AttoVote>
) : AttoMessage {
    companion object {
        const val MAX_COUNT = 20L
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.VOTE_RESPONSE
    }

    override fun isValid(network: AttoNetwork): Boolean {
        return votes.isNotEmpty() && votes.size <= MAX_COUNT && votes.all { it.isValid(blockHash) }
    }

}

