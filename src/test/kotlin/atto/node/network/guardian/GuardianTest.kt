package atto.node.network.guardian

import atto.node.EventPublisher
import atto.node.network.InboundNetworkMessage
import atto.node.network.NodeBanned
import atto.node.network.peer.Peer
import atto.node.network.peer.PeerAdded
import atto.node.network.peer.PeerRemoved
import atto.node.vote.weight.VoteWeighter
import atto.protocol.AttoNode
import atto.protocol.NodeFeature
import atto.protocol.vote.AttoVoteRequest
import cash.atto.commons.*
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

@ExtendWith(MockKExtension::class)
class GuardianTest {

    @RelaxedMockK
    lateinit var voteWeighter: VoteWeighter

    @RelaxedMockK
    lateinit var eventPublisher: EventPublisher

    @InjectMockKs
    lateinit var guardian: Guardian

    @BeforeEach
    fun beforeEach() {
        guardian.clear()
    }

    @Test
    fun `should take snapshot`() {
        // given
        val peer = createPeer(AttoAmount.MAX)
        guardian.add(PeerAdded(peer))
        guardian.count(inboundMessage(peer.connectionSocketAddress))

        // when
        guardian.guard()

        // then
        assertEquals(mapOf(peer.connectionSocketAddress to 1UL), guardian.getSnapshot())
        verify(exactly = 0) { eventPublisher.publish(any()) }
    }

    @Test
    fun `should ban when node deviate from single voter`() {
        // given
        val votePeer = createPeer(AttoAmount.MAX)
        guardian.add(PeerAdded(votePeer))
        guardian.count(inboundMessage(votePeer.connectionSocketAddress))

        val normalPeer = createPeer(AttoAmount.MAX)
        guardian.add(PeerAdded(normalPeer))
        for (i in 0..Guardian.toleranceMultiplier.toInt()) {
            guardian.count(inboundMessage(normalPeer.connectionSocketAddress))
        }

        every { voteWeighter.isAboveMinimalRebroadcastWeight(votePeer.node.publicKey) } returns true


        // when
        guardian.guard()

        // then
        verify { eventPublisher.publish(NodeBanned(normalPeer.connectionSocketAddress.address)) }
    }

    @Test
    fun `should ban when node deviate from voters median`() {
        // given
        val votePeer1 = createPeer(AttoAmount(ULong.MAX_VALUE / 2U))
        guardian.add(PeerAdded(votePeer1))
        guardian.count(inboundMessage(votePeer1.connectionSocketAddress))

        val votePeer2 = createPeer(AttoAmount(ULong.MAX_VALUE / 2U))
        guardian.add(PeerAdded(votePeer2))
        guardian.count(inboundMessage(votePeer2.connectionSocketAddress))

        val normalPeer = createPeer(AttoAmount.MAX)
        guardian.add(PeerAdded(normalPeer))
        for (i in 0U..Guardian.toleranceMultiplier) {
            guardian.count(inboundMessage(normalPeer.connectionSocketAddress))
        }

        every { voteWeighter.isAboveMinimalRebroadcastWeight(votePeer1.node.publicKey) } returns true
        every { voteWeighter.isAboveMinimalRebroadcastWeight(votePeer2.node.publicKey) } returns true


        // when
        guardian.guard()

        // then
        verify { eventPublisher.publish(NodeBanned(normalPeer.connectionSocketAddress.address)) }
    }

    @Test
    fun `should remove voter when disconnected`() {
        // given
        val peer = createPeer(AttoAmount.MAX)
        guardian.add(PeerAdded(peer))

        // when
        guardian.remove(PeerRemoved(peer))

        // then
        assertThat(guardian.getVoters()).isEmpty()
    }


    private fun createPeer(weight: AttoAmount): Peer {
        val socketAddress = randomSocketAddress()
        val node = createNode(socketAddress)
        val peer = Peer(socketAddress, node)
        every { voteWeighter.get(peer.node.publicKey) } returns weight
        return peer
    }

    private fun createNode(socketAddress: InetSocketAddress): AttoNode {
        return AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            socketAddress = socketAddress,
            features = setOf(NodeFeature.VOTING, NodeFeature.HISTORICAL)
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