package org.atto.protocol.network.codec.peer

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.MessageType
import org.atto.protocol.network.codec.MessageCodec
import org.atto.protocol.network.peer.KeepAlive
import java.util.stream.Collectors
import java.util.stream.Stream

class KeepAliveCodec : MessageCodec<KeepAlive> {
    var emptySocketAddress = AttoByteBuffer(18).getInetSocketAddress()

    override fun messageType(): MessageType {
        return MessageType.KEEP_ALIVE
    }

    override fun targetClass(): Class<KeepAlive> {
        return KeepAlive::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): KeepAlive? {
        if (byteBuffer.size < KeepAlive.size) {
            return null
        }

        val neighbours = Stream.iterate(0) { i -> i + 18 }
            .limit(8)
            .map { i -> byteBuffer.getInetSocketAddress() }
            .filter { it != emptySocketAddress }
            .collect(Collectors.toList())

        return KeepAlive(neighbours = neighbours)
    }

    override fun toByteBuffer(t: KeepAlive): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(KeepAlive.size)

        for (neighbour in t.neighbours) {
            byteBuffer.add(neighbour)
        }

        return byteBuffer
    }
}