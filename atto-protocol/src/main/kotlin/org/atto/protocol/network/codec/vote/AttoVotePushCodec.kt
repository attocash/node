package org.atto.protocol.network.codec.vote

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.AttoMessageType
import org.atto.protocol.network.codec.AttoMessageCodec
import org.atto.protocol.vote.AttoVotePush

class AttoVotePushCodec(private val hashVoteCodec: AttoHashVoteCodec) : AttoMessageCodec<AttoVotePush> {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.VOTE_PUSH
    }

    override fun targetClass(): Class<AttoVotePush> {
        return AttoVotePush::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoVotePush? {
        if (byteBuffer.size < AttoVotePush.size) {
            return null
        }

        val hashVote = hashVoteCodec.fromByteBuffer(byteBuffer) ?: return null

        return AttoVotePush(
            vote = hashVote
        )
    }

    override fun toByteBuffer(t: AttoVotePush): AttoByteBuffer {
        return hashVoteCodec.toByteBuffer(t.vote)
    }
}