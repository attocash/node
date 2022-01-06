package org.atto.protocol.network.codec.transaction

import org.atto.commons.AttoNetwork
import org.atto.protocol.network.codec.Codec
import org.atto.protocol.transaction.Transaction

class TransactionCodec(val network: AttoNetwork) : Codec<Transaction> {
    override fun fromByteArray(byteArray: ByteArray): Transaction? {
        return Transaction.fromByteArray(network, byteArray)
    }

    override fun toByteArray(t: Transaction): ByteArray {
        return t.toByteArray()
    }
}