package org.atto.protocol.network.codec.transaction

import org.atto.commons.AttoByteBuffer
import org.atto.commons.AttoNetwork
import org.atto.protocol.network.codec.Codec
import org.atto.protocol.transaction.Transaction

class TransactionCodec(val network: AttoNetwork) : Codec<Transaction> {
    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): Transaction? {
        return Transaction.fromByteBuffer(network, byteBuffer)
    }

    override fun toByteBuffer(t: Transaction): AttoByteBuffer {
        return t.toByteBuffer()
    }
}