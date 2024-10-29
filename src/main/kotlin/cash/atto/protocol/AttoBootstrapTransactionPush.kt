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
@SerialName("AttoBootstrapTransactionPush")
data class AttoBootstrapTransactionPush(
    @ProtoNumber(0)
    @Serializable(with = AttoTransactionAsByteArraySerializer::class)
    val transaction: AttoTransaction,
) : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.BOOTSTRAP_TRANSACTION_PUSH

    override fun isValid(network: AttoNetwork): Boolean = transaction.isValid() && transaction.block.network == network
}
