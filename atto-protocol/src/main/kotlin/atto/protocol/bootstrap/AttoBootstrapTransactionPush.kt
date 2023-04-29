package atto.protocol.bootstrap

import atto.commons.AttoTransaction
import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType


data class AttoBootstrapTransactionPush(val transaction: AttoTransaction) : AttoMessage {
    companion object {
        val size = AttoTransaction.size
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.BOOTSTRAP_TRANSACTION_PUSH
    }

}

