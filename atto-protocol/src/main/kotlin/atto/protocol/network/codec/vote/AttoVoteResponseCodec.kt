package atto.protocol.network.codec.vote

import atto.commons.AttoByteBuffer
import atto.protocol.network.AttoMessageType
import atto.protocol.network.codec.AttoMessageCodec
import atto.protocol.vote.AttoVote
import atto.protocol.vote.AttoVoteResponse

class AttoVoteResponseCodec(private val voteCodec: AttoVoteCodec) : AttoMessageCodec<AttoVoteResponse> {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.VOTE_RESPONSE
    }

    override fun targetClass(): Class<AttoVoteResponse> {
        return AttoVoteResponse::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoVoteResponse? {
        val count = byteBuffer.getUShort().toInt()
        if (count == 0 || count > AttoVoteResponse.maxCount) {
            return null
        }

        val votes = ArrayList<AttoVote>(count)
        var i = byteBuffer.getIndex()
        for (j in 0 until count) {
            val vote = voteCodec.fromByteBuffer(byteBuffer.slice(i)) ?: return null
            votes.add(vote)
            i += AttoVote.size

        }

        return AttoVoteResponse(votes)
    }

    override fun toByteBuffer(t: AttoVoteResponse): AttoByteBuffer {
        val votes = t.votes
        val byteBuffers = votes.map { voteCodec.toByteBuffer(it) }
        return AttoByteBuffer(byteBuffers.sumOf { it.size } + 2)
            .add(votes.size.toUShort())
            .add(byteBuffers)
    }
}