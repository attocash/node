package atto.protocol.network.codec.transaction

import atto.protocol.network.codec.AttoCodec
import cash.atto.commons.AttoByteBuffer
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoTransaction

class AttoTransactionCodec(val network: AttoNetwork) : AttoCodec<AttoTransaction> {
    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoTransaction? {
        return AttoTransaction.fromByteBuffer(network, byteBuffer)
    }

    override fun toByteBuffer(t: AttoTransaction): AttoByteBuffer {
        return t.toByteBuffer()
    }
}