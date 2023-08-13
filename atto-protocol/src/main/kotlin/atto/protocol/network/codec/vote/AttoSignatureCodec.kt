package atto.protocol.network.codec.vote

import atto.protocol.network.codec.AttoCodec
import atto.protocol.vote.AttoVoteSignature
import cash.atto.commons.AttoByteBuffer

class AttoSignatureCodec : AttoCodec<AttoVoteSignature> {
    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoVoteSignature? {
        return AttoVoteSignature.fromByteBuffer(byteBuffer)
    }

    override fun toByteBuffer(t: AttoVoteSignature): AttoByteBuffer {
        return t.toByteBuffer()
    }
}