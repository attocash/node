package org.atto.protocol.transaction

import org.atto.commons.AttoTransaction
import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.MessageType


data class TransactionPush(val transaction: Transaction) : AttoMessage {
    companion object {
        val size = AttoTransaction.size
    }

    override fun messageType(): MessageType {
        return MessageType.TRANSACTION_PUSH
    }

}

