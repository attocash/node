package org.atto.protocol.network.codec.vote

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.MessageType
import org.atto.protocol.network.codec.MessageCodec
import org.atto.protocol.vote.VotePush

class VotePushCodec(private val hashVoteCodec: HashVoteCodec) : MessageCodec<VotePush> {

    override fun messageType(): MessageType {
        return MessageType.VOTE_PUSH
    }

    override fun targetClass(): Class<VotePush> {
        return VotePush::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): VotePush? {
        if (byteBuffer.size < VotePush.size) {
            return null
        }

        val hashVote = hashVoteCodec.fromByteBuffer(byteBuffer) ?: return null

        return VotePush(
            hashVote = hashVote
        )
    }

    override fun toByteBuffer(t: VotePush): AttoByteBuffer {
        return hashVoteCodec.toByteBuffer(t.hashVote)
    }
}