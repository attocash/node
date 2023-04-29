package atto.protocol.network.codec.peer

import atto.commons.AttoByteBuffer
import atto.protocol.network.AttoMessageType
import atto.protocol.network.codec.AttoMessageCodec
import atto.protocol.network.peer.AttoKeepAlive
import java.util.stream.Collectors
import java.util.stream.Stream

class AttoKeepAliveCodec : AttoMessageCodec<AttoKeepAlive> {
    var emptySocketAddress = AttoByteBuffer(18).getInetSocketAddress()

    override fun messageType(): AttoMessageType {
        return AttoMessageType.KEEP_ALIVE
    }

    override fun targetClass(): Class<AttoKeepAlive> {
        return AttoKeepAlive::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoKeepAlive? {
        if (byteBuffer.size < AttoKeepAlive.size) {
            return null
        }

        val neighbours = Stream.iterate(0) { i -> i + 18 }
            .limit(8)
            .map { _ -> byteBuffer.getInetSocketAddress() }
            .filter { it != emptySocketAddress }
            .collect(Collectors.toList())

        return AttoKeepAlive(neighbours = neighbours)
    }

    override fun toByteBuffer(t: AttoKeepAlive): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(AttoKeepAlive.size)

        for (neighbour in t.neighbours) {
            byteBuffer.add(neighbour)
        }

        return byteBuffer
    }
}