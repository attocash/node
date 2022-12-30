package org.atto.protocol.network.codec

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.AttoNode

class AttoNodeCodec : AttoCodec<AttoNode> {

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoNode? {
        return AttoNode.fromByteBuffer(byteBuffer)
    }

    override fun toByteBuffer(t: AttoNode): AttoByteBuffer {
        return t.toByteBuffer()
    }

}