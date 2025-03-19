package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoTransaction
import cash.atto.commons.serialiazer.AttoTransactionAsByteArraySerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("AttoTransactionResponse")
data class AttoTransactionResponse(
    @ProtoNumber(1)
    @Serializable(with = AttoTransactionAsByteArraySerializer::class)
    val transaction: AttoTransaction,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.TRANSACTION_RESPONSE

    override fun isValid(network: AttoNetwork): Boolean = transaction.isValid() && transaction.block.network == network
}
