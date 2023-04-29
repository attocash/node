package atto.node.network.guardian

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import atto.commons.AttoAmount
import atto.commons.AttoHash
import atto.commons.AttoNetwork
import atto.commons.AttoPublicKey
import atto.node.EventPublisher
import atto.node.network.InboundNetworkMessage
import atto.node.network.peer.Peer
import atto.node.network.peer.PeerAdded
import atto.node.vote.weight.VoteWeighter
import atto.protocol.AttoNode
import atto.protocol.NodeFeature
import atto.protocol.vote.AttoVoteRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

@ExtendWith(MockKExtension::class)
class GuardianTest {

    @MockK
    lateinit var voteWeighter: VoteWeighter

    @MockK
    lateinit var eventPublisher: EventPublisher

    @InjectMockKs
    lateinit var guardian: Guardian

    @Test
    @Disabled("https://github.com/mockk/mockk/issues/859")
    fun `should take snapshot`() {
        // given
        val peerAdded = createPeerAdded()
        val peer = peerAdded.peer
        guardian.add(peerAdded)
        guardian.count(inboundMessage(peer.connectionSocketAddress))

        every { voteWeighter.get(peer.node.publicKey) } returns AttoAmount.MAX


        // when
        guardian.guard()

        // then

        assertEquals(mapOf(peer.connectionSocketAddress.address to 1U), guardian.getSnapshot())

    }


    private fun createPeerAdded(): PeerAdded {
        val socketAddress = randomSocketAddress()
        val node = createNode(socketAddress)
        val peer = Peer(socketAddress, node)
        return PeerAdded(peer)
    }

    private fun createNode(socketAddress: InetSocketAddress): atto.protocol.AttoNode {
        return atto.protocol.AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            socketAddress = socketAddress,
            features = setOf(atto.protocol.NodeFeature.VOTING, atto.protocol.NodeFeature.HISTORICAL)
        )
    }

    private fun randomSocketAddress(): InetSocketAddress {
        val address = Random.Default.nextBytes(ByteArray(4))
        return InetSocketAddress(InetAddress.getByAddress(address), Random.nextInt(65535))
    }

    private fun inboundMessage(socketAddress: InetSocketAddress): InboundNetworkMessage<*> {
        return InboundNetworkMessage(socketAddress, AttoVoteRequest(AttoHash(ByteArray(32))))
    }
}