package org.atto.protocol.network.codec.bootstrap

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.bootstrap.AttoBootstrapTransactionPush
import org.atto.protocol.network.AttoMessageType
import org.atto.protocol.network.codec.AttoMessageCodec
import org.atto.protocol.network.codec.transaction.AttoTransactionCodec
import org.atto.protocol.transaction.AttoTransactionPush

class AttoBootstrapTransactionPushCodec(private val transactionCodec: AttoTransactionCodec) :
    AttoMessageCodec<AttoBootstrapTransactionPush> {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.BOOTSTRAP_TRANSACTION_PUSH
    }

    override fun targetClass(): Class<AttoBootstrapTransactionPush> {
        return AttoBootstrapTransactionPush::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoBootstrapTransactionPush? {
        if (byteBuffer.size < AttoTransactionPush.size) {
            return null
        }

        val transaction = transactionCodec.fromByteBuffer(byteBuffer) ?: return null

        return AttoBootstrapTransactionPush(
            transaction = transaction
        )
    }

    override fun toByteBuffer(t: AttoBootstrapTransactionPush): AttoByteBuffer {
        return transactionCodec.toByteBuffer(t.transaction)
    }
}