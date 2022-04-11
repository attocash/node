package org.atto.protocol.network.codec.vote

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.codec.Codec
import org.atto.protocol.vote.Vote

class VoteCodec : Codec<Vote> {
    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): Vote? {
        return Vote.fromByteBuffer(byteBuffer)
    }

    override fun toByteBuffer(t: Vote): AttoByteBuffer {
        return t.toByteBuffer()
    }
}