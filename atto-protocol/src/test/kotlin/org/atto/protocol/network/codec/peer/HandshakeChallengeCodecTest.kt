package org.atto.protocol.network.codec.peer

import org.atto.protocol.network.ContextHolder
import org.atto.protocol.network.codec.peer.handshake.HandshakeChallengeCodec
import org.atto.protocol.network.handshake.HandshakeChallenge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

internal class HandshakeChallengeCodecTest {
    val codec = HandshakeChallengeCodec()

    @Test
    fun `should serialize and deserialize`() {
        // given
        val socketAddress = InetSocketAddress(InetAddress.getLocalHost(), 8330)
        ContextHolder.put("socketAddress", socketAddress)

        val expectedHandshakeChallenge = HandshakeChallenge(
            value = Random.nextBytes(ByteArray(16))
        )

        // when
        val byteBuffer = codec.toByteBuffer(expectedHandshakeChallenge)
        val handshakeChallenge = codec.fromByteBuffer(byteBuffer)

        // then
        assertEquals(expectedHandshakeChallenge, handshakeChallenge)
    }
}