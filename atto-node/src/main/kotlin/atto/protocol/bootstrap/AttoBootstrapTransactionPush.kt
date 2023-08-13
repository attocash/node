package atto.protocol.bootstrap

import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType
import cash.atto.commons.AttoTransaction


data class AttoBootstrapTransactionPush(val transaction: AttoTransaction) : AttoMessage {
    companion object {
        val size = AttoTransaction.size
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.BOOTSTRAP_TRANSACTION_PUSH
    }

}

