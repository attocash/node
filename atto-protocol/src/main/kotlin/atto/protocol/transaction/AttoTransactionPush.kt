package atto.protocol.transaction

import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType
import cash.atto.commons.AttoTransaction


data class AttoTransactionPush(val transaction: AttoTransaction) : AttoMessage {
    companion object {
        val size = AttoTransaction.size
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_PUSH
    }

}

