package org.atto.protocol.transaction

import org.atto.commons.AttoTransaction
import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.AttoMessageType


data class AttoTransactionPush(val transaction: AttoTransaction) : AttoMessage {
    companion object {
        val size = AttoTransaction.size
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_PUSH
    }

}

