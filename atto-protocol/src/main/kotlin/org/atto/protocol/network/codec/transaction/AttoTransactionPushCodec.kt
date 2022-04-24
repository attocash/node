package org.atto.protocol.network.codec.transaction

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.AttoMessageType
import org.atto.protocol.network.codec.AttoMessageCodec
import org.atto.protocol.transaction.AttoTransactionPush

class AttoTransactionPushCodec(private val transactionCodec: AttoTransactionCodec) :
    AttoMessageCodec<AttoTransactionPush> {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_PUSH
    }

    override fun targetClass(): Class<AttoTransactionPush> {
        return AttoTransactionPush::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoTransactionPush? {
        if (byteBuffer.size < AttoTransactionPush.size) {
            return null
        }

        val transaction = transactionCodec.fromByteBuffer(byteBuffer) ?: return null

        return AttoTransactionPush(
            transaction = transaction
        )
    }

    override fun toByteBuffer(t: AttoTransactionPush): AttoByteBuffer {
        return transactionCodec.toByteBuffer(t.transaction)
    }
}