package cash.atto.protocol

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.serialiazer.AttoHashAsByteArraySerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("AttoVoteStreamRequest")
data class AttoVoteStreamRequest(
    @ProtoNumber(1)
    @Serializable(with = AttoHashAsByteArraySerializer::class)
    val blockHash: AttoHash,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.VOTE_STREAM_REQUEST

    override fun isValid(network: AttoNetwork): Boolean = blockHash.isValid()
}
