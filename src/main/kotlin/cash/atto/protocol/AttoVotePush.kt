package cash.atto.protocol

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.serialiazers.AttoHashAsByteArraySerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("AttoVotePush")
data class AttoVotePush(
    @ProtoNumber(0)
    @Serializable(with = AttoHashAsByteArraySerializer::class)
    val blockHash: AttoHash,
    @ProtoNumber(1) val vote: AttoVote,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.VOTE_PUSH

    override fun isValid(network: AttoNetwork): Boolean = vote.isValid(blockHash)
}
