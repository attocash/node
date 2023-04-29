package atto.protocol.transaction

import atto.commons.AttoHash
import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType


data class AttoTransactionRequest(val hash: AttoHash) : AttoMessage {
    companion object {
        val size = AttoHash.defaultSize
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_REQUEST
    }

}

