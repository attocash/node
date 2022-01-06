package org.atto.protocol.network.codec.transaction

import org.atto.protocol.network.MessageType
import org.atto.protocol.network.codec.MessageCodec
import org.atto.protocol.transaction.TransactionPush

class TransactionPushCodec(private val transactionCodec: TransactionCodec) : MessageCodec<TransactionPush> {

    override fun messageType(): MessageType {
        return MessageType.TRANSACTION_PUSH
    }

    override fun targetClass(): Class<TransactionPush> {
        return TransactionPush::class.java
    }

    override fun fromByteArray(byteArray: ByteArray): TransactionPush? {
        if (byteArray.size < TransactionPush.size) {
            return null
        }

        val transaction = transactionCodec.fromByteArray(byteArray) ?: return null

        return TransactionPush(
            transaction = transaction
        )
    }

    override fun toByteArray(t: TransactionPush): ByteArray {
        return transactionCodec.toByteArray(t.transaction)
    }
}