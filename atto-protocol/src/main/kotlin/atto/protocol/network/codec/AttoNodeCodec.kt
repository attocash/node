package atto.protocol.network.codec

import cash.atto.commons.AttoByteBuffer

class AttoNodeCodec : AttoCodec<atto.protocol.AttoNode> {

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): atto.protocol.AttoNode? {
        return atto.protocol.AttoNode.fromByteBuffer(byteBuffer)
    }

    override fun toByteBuffer(t: atto.protocol.AttoNode): AttoByteBuffer {
        return t.toByteBuffer()
    }

}