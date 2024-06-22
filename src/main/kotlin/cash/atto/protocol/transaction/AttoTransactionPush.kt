package cash.atto.protocol.transaction

import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoTransaction
import cash.atto.protocol.AttoMessage
import cash.atto.protocol.AttoMessageType
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
