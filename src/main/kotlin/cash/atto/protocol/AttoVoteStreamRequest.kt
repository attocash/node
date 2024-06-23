package cash.atto.protocol

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("VOTE_STREAM_REQUEST")
data class AttoVoteStreamRequest(
    @ProtoNumber(0) @Contextual val blockHash: AttoHash,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.VOTE_STREAM_REQUEST

    override fun isValid(network: AttoNetwork): Boolean = blockHash.isValid()
}
