package org.atto.protocol.transaction

import org.atto.commons.AttoHash
import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.AttoMessageType


data class AttoTransactionRequest(val hash: AttoHash) : AttoMessage {
    companion object {
        val size = AttoHash.defaultSize
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_REQUEST
    }

}

