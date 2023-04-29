package atto.protocol.transaction

import atto.commons.AttoTransaction
import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType


data class AttoTransactionPush(val transaction: AttoTransaction) : AttoMessage {
    companion object {
        val size = AttoTransaction.size
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_PUSH
    }

}

