package org.atto.protocol.network.codec.vote

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.codec.AttoCodec
import org.atto.protocol.vote.AttoVoteSignature

class AttoVoteCodec : AttoCodec<AttoVoteSignature> {
    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoVoteSignature? {
        return AttoVoteSignature.fromByteBuffer(byteBuffer)
    }

    override fun toByteBuffer(t: AttoVoteSignature): AttoByteBuffer {
        return t.toByteBuffer()
    }
}