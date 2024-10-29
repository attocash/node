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
@SerialName("AttoVoteStreamCancel")
data class AttoVoteStreamCancel(
    @ProtoNumber(0)
    @Serializable(with = AttoHashAsByteArraySerializer::class)
    val blockHash: AttoHash,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.VOTE_STREAM_CANCEL

    override fun isValid(network: AttoNetwork): Boolean = blockHash.isValid()
}
