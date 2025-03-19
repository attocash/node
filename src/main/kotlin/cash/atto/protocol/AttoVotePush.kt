package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoSignedVote
import cash.atto.commons.serialiazer.AttoSignedVoteAsByteArraySerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("AttoVotePush")
data class AttoVotePush(
    @ProtoNumber(1)
    @Serializable(with = AttoSignedVoteAsByteArraySerializer::class)
    val vote: AttoSignedVote,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.VOTE_PUSH

    override fun isValid(network: AttoNetwork): Boolean = vote.isValid()
}
