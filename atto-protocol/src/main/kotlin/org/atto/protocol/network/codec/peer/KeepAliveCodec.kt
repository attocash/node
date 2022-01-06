package org.atto.protocol.network.codec.peer

import org.atto.protocol.network.MessageType
import org.atto.protocol.network.codec.MessageCodec
import org.atto.protocol.network.peer.KeepAlive
import org.atto.protocol.toByteArray
import org.atto.protocol.toInetSocketAddress
import java.nio.ByteBuffer
import java.util.stream.Collectors
import java.util.stream.Stream

class KeepAliveCodec : MessageCodec<KeepAlive> {
    var zeros18 = ByteArray(18)

    override fun messageType(): MessageType {
        return MessageType.KEEP_ALIVE
    }

    override fun targetClass(): Class<KeepAlive> {
        return KeepAlive::class.java
    }

    override fun fromByteArray(byteArray: ByteArray): KeepAlive? {
        if (byteArray.size < KeepAlive.size) {
            return null
        }

        val neighbours = Stream.iterate(0) { i -> i + 18 }
            .limit(8)
            .map { i -> byteArray.sliceArray(i until i + 18) }
            .filter { !it.contentEquals(zeros18) }
            .map { it.toInetSocketAddress() }
            .collect(Collectors.toList())

        return KeepAlive(neighbours = neighbours)
    }

    override fun toByteArray(t: KeepAlive): ByteArray {
        val byteBuffer = ByteBuffer.allocate(KeepAlive.size)

        for (neighbour in t.neighbours) {
            byteBuffer.put(neighbour.toByteArray())
        }

        return byteBuffer.array()
    }
}