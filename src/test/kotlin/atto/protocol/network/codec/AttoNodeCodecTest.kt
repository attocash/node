package atto.protocol.network.codec

import atto.protocol.AttoNode
import atto.protocol.NodeFeature
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.random.Random

internal class AttoNodeCodecTest {

    val codec = AttoNodeCodec()

    @Test
    fun `should serialize and deserialize`() {
        // given
        val expectedNode = AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            socketAddress = InetSocketAddress(8080),
            features = setOf(NodeFeature.VOTING, NodeFeature.HISTORICAL)
        )

        // when
        val byteBuffer = codec.toByteBuffer(expectedNode)
        val node = codec.fromByteBuffer(byteBuffer)!!

        // then
        assertEquals(expectedNode, node)
    }
}