package atto.protocol.network.codec.transaction

import atto.commons.AttoByteBuffer
import atto.commons.AttoNetwork
import atto.commons.AttoTransaction
import atto.protocol.network.codec.AttoCodec

class AttoTransactionCodec(val network: AttoNetwork) : AttoCodec<AttoTransaction> {
    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoTransaction? {
        return AttoTransaction.fromByteBuffer(network, byteBuffer)
    }

    override fun toByteBuffer(t: AttoTransaction): AttoByteBuffer {
        return t.byteBuffer
    }
}