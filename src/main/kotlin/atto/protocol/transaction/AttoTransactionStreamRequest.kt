package atto.protocol.transaction

import atto.protocol.AttoMessage
import atto.protocol.AttoMessageType
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AttoTransactionStreamRequest(
    @ProtoNumber(0) @Contextual val publicKey: AttoPublicKey,
    @ProtoNumber(1) val startHeight: ULong,
    @ProtoNumber(2) val endHeight: ULong,
) : AttoMessage {
    companion object {
        const val MAX_TRANSACTIONS = 1000UL
    }

    override fun messageType(): AttoMessageType = AttoMessageType.TRANSACTION_STREAM_REQUEST

    override fun isValid(network: AttoNetwork): Boolean = startHeight < endHeight && endHeight - startHeight <= MAX_TRANSACTIONS
}
