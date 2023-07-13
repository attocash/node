package atto.protocol.network.codec.peer

import atto.protocol.network.peer.AttoKeepAlive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

internal class AttoAttoKeepAliveCodecTest {
    val codec = AttoKeepAliveCodec()

    @Test
    fun `should serialize and deserialize`() {
        // given
        val neighbors = arrayListOf(InetSocketAddress(InetAddress.getLocalHost(), 8330))
        val expectedKeepAlive = AttoKeepAlive(
            neighbours = neighbors
        )

        // when
        val byteBuffer = codec.toByteBuffer(expectedKeepAlive)
        val keepAlive = codec.fromByteBuffer(byteBuffer)

        // then
        assertEquals(expectedKeepAlive, keepAlive)
    }
}