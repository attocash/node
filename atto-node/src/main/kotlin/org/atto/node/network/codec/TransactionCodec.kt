package org.atto.node.network.codec

import org.atto.commons.AttoByteBuffer
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.toTransaction
import org.atto.protocol.network.codec.AttoCodec
import org.atto.protocol.network.codec.transaction.AttoTransactionCodec

class TransactionCodec(private val attoTransactionCodec: AttoTransactionCodec) : AttoCodec<Transaction> {
    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): Transaction? {
        return attoTransactionCodec.fromByteBuffer(byteBuffer)?.toTransaction()
    }

    override fun toByteBuffer(t: Transaction): AttoByteBuffer {
        return t.toAttoTransaction().byteBuffer
    }
}