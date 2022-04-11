package org.atto.protocol.network.codec.transaction

import org.atto.commons.AttoByteBuffer
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

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): TransactionPush? {
        if (byteBuffer.size < TransactionPush.size) {
            return null
        }

        val transaction = transactionCodec.fromByteBuffer(byteBuffer) ?: return null

        return TransactionPush(
            transaction = transaction
        )
    }

    override fun toByteBuffer(t: TransactionPush): AttoByteBuffer {
        return transactionCodec.toByteBuffer(t.transaction)
    }
}