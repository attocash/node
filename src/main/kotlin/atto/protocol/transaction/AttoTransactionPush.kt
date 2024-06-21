package atto.protocol.transaction

import atto.protocol.AttoMessage
import atto.protocol.AttoMessageType
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoTransaction
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AttoTransactionPush(
    @ProtoNumber(0) val transaction: AttoTransaction,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.TRANSACTION_PUSH

    override fun isValid(network: AttoNetwork): Boolean = transaction.isValid(network)
}
