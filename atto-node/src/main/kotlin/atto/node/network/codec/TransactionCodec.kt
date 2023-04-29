package atto.node.network.codec

import atto.commons.AttoByteBuffer
import atto.node.transaction.Transaction
import atto.node.transaction.toTransaction
import atto.protocol.network.codec.AttoCodec
import atto.protocol.network.codec.transaction.AttoTransactionCodec

class TransactionCodec(private val attoTransactionCodec: AttoTransactionCodec) : AttoCodec<Transaction> {
    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): Transaction? {
        return attoTransactionCodec.fromByteBuffer(byteBuffer)?.toTransaction()
    }

    override fun toByteBuffer(t: Transaction): AttoByteBuffer {
        return t.toAttoTransaction().byteBuffer
    }
}