package org.atto.protocol.network.codec.transaction

import org.atto.commons.AttoByteBuffer
import org.atto.commons.AttoNetwork
import org.atto.commons.AttoTransaction
import org.atto.protocol.network.codec.AttoCodec

class AttoTransactionCodec(val network: AttoNetwork) : AttoCodec<AttoTransaction> {
    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoTransaction? {
        return AttoTransaction.fromByteBuffer(network, byteBuffer)
    }

    override fun toByteBuffer(t: AttoTransaction): AttoByteBuffer {
        return t.byteBuffer
    }
}