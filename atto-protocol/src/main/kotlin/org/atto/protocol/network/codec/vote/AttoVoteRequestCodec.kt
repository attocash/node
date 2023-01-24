package org.atto.protocol.network.codec.vote

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.AttoMessageType
import org.atto.protocol.network.codec.AttoMessageCodec
import org.atto.protocol.vote.AttoVoteRequest

class AttoVoteRequestCodec : AttoMessageCodec<AttoVoteRequest> {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.VOTE_REQUEST
    }

    override fun targetClass(): Class<AttoVoteRequest> {
        return AttoVoteRequest::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoVoteRequest? {
        if (byteBuffer.size < AttoVoteRequest.size) {
            return null
        }

        return AttoVoteRequest(
            hash = byteBuffer.getHash()
        )
    }

    override fun toByteBuffer(t: AttoVoteRequest): AttoByteBuffer {
        return AttoByteBuffer(32).add(t.hash)
    }
}