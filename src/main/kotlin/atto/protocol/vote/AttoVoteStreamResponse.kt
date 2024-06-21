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
data class AttoVoteStreamResponse(
    @ProtoNumber(0) val blockHash: AttoHash,
    @ProtoNumber(1) val vote: AttoVote,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.VOTE_STREAM_RESPONSE

    override fun isValid(network: AttoNetwork): Boolean = vote.isValid(blockHash) && vote.isFinal()
}
