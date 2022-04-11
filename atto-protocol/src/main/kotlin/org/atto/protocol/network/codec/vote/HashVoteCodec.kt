package org.atto.protocol.network.codec.vote

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.codec.Codec
import org.atto.protocol.vote.HashVote
import org.atto.protocol.vote.VoteType
import java.time.Instant

class HashVoteCodec(private val voteCodec: VoteCodec) : Codec<HashVote> {

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): HashVote? {
        if (byteBuffer.size < HashVote.size) {
            return null
        }

        val type = VoteType.from(byteBuffer.getUByte())

        if (type == VoteType.UNKNOWN) {
            return null
        }

        val hash = byteBuffer.getHash()
        val vote = voteCodec.fromByteBuffer(byteBuffer.slice(33))!!

        val hashVote = HashVote(
            type = type,
            hash = hash,
            vote = vote,
            receivedTimestamp = Instant.now()
        )

        if (!hashVote.isValid()) {
            return null
        }

        return hashVote
    }

    override fun toByteBuffer(t: HashVote): AttoByteBuffer {
        return AttoByteBuffer(HashVote.size)
            .add(t.type.code)
            .add(t.hash)
            .add(voteCodec.toByteBuffer(t.vote))
    }
}