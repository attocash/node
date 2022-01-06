package org.atto.protocol.network.codec.vote

import org.atto.commons.toByteArray
import org.atto.protocol.network.codec.Codec
import org.atto.protocol.vote.Vote
import java.nio.ByteBuffer

class VoteCodec : Codec<Vote> {
    override fun fromByteArray(byteArray: ByteArray): Vote? {
        return Vote.fromByteArray(byteArray)
    }

    override fun toByteArray(t: Vote): ByteArray {
        return ByteBuffer.allocate(Vote.size)
            .put(t.timestamp.toByteArray())
            .put(t.publicKey.value)
            .put(t.signature.value)
            .array()
    }
}