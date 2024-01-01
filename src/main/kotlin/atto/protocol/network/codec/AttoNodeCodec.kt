package atto.protocol.network.codec

import atto.protocol.AttoNode
import cash.atto.commons.AttoByteBuffer

class AttoNodeCodec : AttoCodec<AttoNode> {

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoNode? {
        return AttoNode.fromByteBuffer(byteBuffer)
    }

    override fun toByteBuffer(t: AttoNode): AttoByteBuffer {
        return t.toByteBuffer()
    }

}