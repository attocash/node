package org.atto.protocol.network.codec.peer

import org.atto.protocol.network.ContextHolder
import org.atto.protocol.network.peer.KeepAlive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

internal class KeepAliveCodecTest {
    val codec = KeepAliveCodec()

    @Test
    fun `should serialize and deserialize`() {
        // given
        val socketAddress = InetSocketAddress(InetAddress.getLocalHost(), 8330)
        ContextHolder.put("socketAddress", socketAddress)

        val neighbors = arrayListOf(InetSocketAddress(InetAddress.getLocalHost(), 8330))
        val expectedKeepAlive = KeepAlive(
            neighbours = neighbors
        )

        // when
        val byteBuffer = codec.toByteBuffer(expectedKeepAlive)
        val keepAlive = codec.fromByteBuffer(byteBuffer)

        // then
        assertEquals(expectedKeepAlive, keepAlive)
    }
}