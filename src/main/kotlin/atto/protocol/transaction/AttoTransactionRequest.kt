package atto.protocol.transaction

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
data class AttoTransactionRequest(@ProtoNumber(0) @Contextual val hash: AttoHash) : AttoMessage {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_REQUEST
    }

    override fun isValid(network: AttoNetwork): Boolean = true

}

