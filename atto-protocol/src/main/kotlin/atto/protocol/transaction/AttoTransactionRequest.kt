package atto.protocol.transaction

import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType
import cash.atto.commons.AttoHash


data class AttoTransactionRequest(val hash: AttoHash) : AttoMessage {
    companion object {
        val size = AttoHash.defaultSize
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_REQUEST
    }

}

