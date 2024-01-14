package atto.protocol.vote

import atto.protocol.AttoMessage
import atto.protocol.AttoMessageType
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AttoVotePush(
    @ProtoNumber(0) @Contextual val blockHash: AttoHash,
    @ProtoNumber(1) val vote: AttoVote
) : AttoMessage {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.VOTE_PUSH
    }

    override fun isValid(network: AttoNetwork): Boolean {
        return vote.isValid(blockHash)
    }

}

