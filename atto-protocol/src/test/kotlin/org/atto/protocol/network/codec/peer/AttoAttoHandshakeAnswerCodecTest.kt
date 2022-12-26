package org.atto.protocol.network.codec.peer

import org.atto.commons.AttoNetwork
import org.atto.commons.AttoPublicKey
import org.atto.commons.AttoSignature
import org.atto.protocol.AttoNode
import org.atto.protocol.NodeFeature
import org.atto.protocol.network.codec.AttoNodeCodec
import org.atto.protocol.network.codec.peer.handshake.AttoHandshakeAnswerCodec
import org.atto.protocol.network.handshake.AttoHandshakeAnswer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

internal class AttoAttoHandshakeAnswerCodecTest {
    val nodeCodec = AttoNodeCodec()
    val codec = AttoHandshakeAnswerCodec(nodeCodec)

    @Test
    fun `should serialize and deserialize`() {
        // given
        val node = AttoNode(
            network = AttoNetwork.LIVE,
            protocolVersion = 0u,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            socketAddress = InetSocketAddress(InetAddress.getLocalHost(), 8330),
            features = setOf(NodeFeature.VOTING, NodeFeature.HISTORICAL)
        )

        val expectedHandshakeAnswer = AttoHandshakeAnswer(
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            node = node
        )

        // when
        val byteBuffer = codec.toByteBuffer(expectedHandshakeAnswer)
        val handshakeAnswer = codec.fromByteBuffer(byteBuffer)

        // then
        assertEquals(expectedHandshakeAnswer, handshakeAnswer)
    }
}