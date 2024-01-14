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
data class AttoTransactionStreamResponse(@ProtoNumber(0) val transaction: AttoTransaction) : AttoMessage {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_STREAM_RESPONSE
    }

    override fun isValid(network: AttoNetwork): Boolean {
        return transaction.isValid(network)
    }
}

