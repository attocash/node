package atto.protocol.network.codec.vote

import atto.protocol.network.codec.AttoCodec
import atto.protocol.vote.AttoVote
import atto.protocol.vote.VoteType
import cash.atto.commons.AttoByteBuffer

class AttoVoteCodec(private val voteCodec: AttoSignatureCodec) : AttoCodec<AttoVote> {

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoVote? {
        if (byteBuffer.size < AttoVote.size) {
            return null
        }

        val type = VoteType.from(byteBuffer.getUByte())

        if (type == VoteType.UNKNOWN) {
            return null
        }

        val hash = byteBuffer.getHash()
        val vote = voteCodec.fromByteBuffer(byteBuffer.slice(33))!!

        val hashVote = AttoVote(
            type = type,
            hash = hash,
            signature = vote,
        )

        if (!hashVote.isValid()) {
            return null
        }

        return hashVote
    }

    override fun toByteBuffer(t: AttoVote): AttoByteBuffer {
        return AttoByteBuffer(AttoVote.size)
            .add(t.type.code)
            .add(t.hash)
            .add(voteCodec.toByteBuffer(t.signature))
    }
}