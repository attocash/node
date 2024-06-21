package atto.node.network.guardian

import atto.node.EventPublisher
import atto.node.network.InboundNetworkMessage
import atto.node.network.MessageSource
import atto.node.network.NodeBanned
import atto.node.network.peer.Peer
import atto.node.network.peer.PeerConnected
import atto.node.network.peer.PeerRemoved
import atto.node.vote.weight.VoteWeighter
import atto.protocol.AttoNode
import atto.protocol.NodeFeature
import atto.protocol.vote.AttoVoteStreamRequest
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
import java.net.URI
import kotlin.random.Random

@ExtendWith(MockKExtension::class)
class GuardianTest {
    private val minimalMedian = 1UL
    private val toleranceMultiplier = 10UL

    @RelaxedMockK
    lateinit var voteWeighter: VoteWeighter

    @RelaxedMockK
    lateinit var eventPublisher: EventPublisher

    @RelaxedMockK
    lateinit var guardianProperties: GuardianProperties

    @InjectMockKs
    lateinit var guardian: Guardian

    @BeforeEach
    fun beforeEach() {
        guardian.clear()
    }

    @Test
    fun `should take snapshot`() {
        // given
        every { guardianProperties.minimalMedian } returns minimalMedian
        every { guardianProperties.toleranceMultiplier } returns toleranceMultiplier

        val peer = createPeer(AttoAmount.MAX)
        guardian.add(PeerConnected(peer))
        guardian.count(inboundMessage(peer.node.publicUri, peer.connectionSocketAddress))

        // when
        guardian.guard()

        // then
        assertEquals(mapOf(peer.connectionSocketAddress to 1UL), guardian.getSnapshot())
        verify(exactly = 0) { eventPublisher.publish(any()) }
    }

    @Test
    fun `should ban when node deviate from single voter`() {
        // given
        every { guardianProperties.minimalMedian } returns minimalMedian
        every { guardianProperties.toleranceMultiplier } returns toleranceMultiplier

        val votePeer = createPeer(AttoAmount.MAX)
        guardian.add(PeerConnected(votePeer))
        guardian.count(inboundMessage(votePeer.node.publicUri, votePeer.connectionSocketAddress))

        val normalPeer = createPeer(AttoAmount.MAX)
        guardian.add(PeerConnected(normalPeer))
        for (i in 0UL..toleranceMultiplier) {
            guardian.count(inboundMessage(normalPeer.node.publicUri, normalPeer.connectionSocketAddress))
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
        every { guardianProperties.minimalMedian } returns minimalMedian
        every { guardianProperties.toleranceMultiplier } returns toleranceMultiplier

        val votePeer1 = createPeer(AttoAmount(ULong.MAX_VALUE / 2U))
        guardian.add(PeerConnected(votePeer1))
        guardian.count(inboundMessage(votePeer1.node.publicUri, votePeer1.connectionSocketAddress))

        val votePeer2 = createPeer(AttoAmount(ULong.MAX_VALUE / 2U))
        guardian.add(PeerConnected(votePeer2))
        guardian.count(inboundMessage(votePeer2.node.publicUri, votePeer2.connectionSocketAddress))

        val normalPeer = createPeer(AttoAmount.MAX)
        guardian.add(PeerConnected(normalPeer))
        for (i in 0UL..toleranceMultiplier) {
            guardian.count(inboundMessage(normalPeer.node.publicUri, normalPeer.connectionSocketAddress))
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
        guardian.add(PeerConnected(peer))

        // when
        guardian.remove(PeerRemoved(peer))

        // then
        assertThat(guardian.getVoters()).isEmpty()
    }

    private fun createPeer(weight: AttoAmount): Peer {
        val socketAddress = randomSocketAddress()
        val publicUri = randomURI()
        val node = createNode(publicUri)
        val peer = Peer(socketAddress, node)
        every { voteWeighter.get(peer.node.publicKey) } returns weight
        return peer
    }

    private fun createNode(publicUri: URI): AttoNode {
        return AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            publicUri = publicUri,
            features = setOf(NodeFeature.VOTING, NodeFeature.HISTORICAL),
        )
    }

    private fun randomSocketAddress(): InetSocketAddress {
        val address = Random.Default.nextBytes(ByteArray(4))
        return InetSocketAddress(InetAddress.getByAddress(address), Random.nextInt(65535))
    }

    private fun randomURI(): URI {
        val port = Random.Default.nextInt(Short.MAX_VALUE.toInt()).toShort()
        return URI("ws://localhost:$port")
    }

    private fun inboundMessage(
        publicUri: URI,
        socketAddress: InetSocketAddress,
    ): InboundNetworkMessage<*> =
        InboundNetworkMessage(
            MessageSource.WEBSOCKET,
            publicUri,
            socketAddress,
            AttoVoteStreamRequest(AttoHash(ByteArray(32))),
        )
}
