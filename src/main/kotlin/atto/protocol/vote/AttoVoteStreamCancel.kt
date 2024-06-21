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
data class AttoVoteStreamCancel(
    @ProtoNumber(0) @Contextual val blockHash: AttoHash,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.VOTE_STREAM_CANCEL

    override fun isValid(network: AttoNetwork): Boolean = blockHash.isValid()
}
