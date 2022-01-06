package org.atto.protocol.network.codec

import org.atto.commons.AttoNetwork
import org.atto.commons.AttoPublicKey
import org.atto.protocol.Node
import org.atto.protocol.NodeFeature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

internal class NodeCodecTest {

    val codec = NodeCodec()

    @Test
    fun `should serialize and deserialize`() {
        // given
        val expectedNode = Node(
            network = AttoNetwork.LIVE,
            protocolVersion = 0u,
            minimalProtocolVersion = 0u,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            socketAddress = InetSocketAddress(InetAddress.getLocalHost(), 8330),
            features = setOf(NodeFeature.VOTING, NodeFeature.HISTORICAL)
        )

        // when
        val byteArray = codec.toByteArray(expectedNode)
        val node = codec.fromByteArray(byteArray)

        // then
        assertEquals(expectedNode, node)
    }
}