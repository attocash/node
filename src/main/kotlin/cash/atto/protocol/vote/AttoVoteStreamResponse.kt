package cash.atto.protocol.vote

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.protocol.AttoMessage
import cash.atto.protocol.AttoMessageType
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
