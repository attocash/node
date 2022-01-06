package org.atto.protocol.network.codec.vote

import org.atto.commons.AttoHash
import org.atto.protocol.network.codec.Codec
import org.atto.protocol.vote.HashVote
import org.atto.protocol.vote.VoteType
import java.nio.ByteBuffer
import java.time.Instant

class HashVoteCodec(private val voteCodec: VoteCodec) : Codec<HashVote> {

    override fun fromByteArray(byteArray: ByteArray): HashVote? {
        if (byteArray.size < HashVote.size) {
            return null
        }

        val type = VoteType.from(byteArray[0].toUByte())

        if (type == VoteType.UNKNOWN) {
            return null
        }

        if (byteArray.size < HashVote.size) {
            return null
        }

        val hash = AttoHash(byteArray.sliceArray(1 until 33))
        val vote = voteCodec.fromByteArray(byteArray.sliceArray(33 until byteArray.size))!!

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

    override fun toByteArray(t: HashVote): ByteArray {

        val byteBuffer = ByteBuffer.allocate(HashVote.size)
            .put(t.type.code.toByte())
            .put(t.hash.value)
            .put(voteCodec.toByteArray(t.vote))

        return byteBuffer.array()
    }
}