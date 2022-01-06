package org.atto.protocol.network.codec.vote

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

    override fun fromByteArray(byteArray: ByteArray): VotePush? {
        if (byteArray.size < VotePush.size) {
            return null
        }

        val hashVote = hashVoteCodec.fromByteArray(byteArray) ?: return null

        return VotePush(
            hashVote = hashVote
        )
    }

    override fun toByteArray(t: VotePush): ByteArray {
        return hashVoteCodec.toByteArray(t.hashVote)
    }
}