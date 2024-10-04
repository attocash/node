package cash.atto.node.network.guardian

import cash.atto.commons.*
import cash.atto.node.EventPublisher
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NodeBanned
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoVoteStreamRequest
import cash.atto.protocol.NodeFeature
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

        val node = createNode(AttoAmount.MAX)
        val event = NodeConnected(randomSocketAddress(), node)
        guardian.add(NodeConnected(randomSocketAddress(), node))
        guardian.count(inboundMessage(node.publicUri, event.connectionSocketAddress))

        // when
        guardian.guard()

        // then
        assertEquals(mapOf(event.connectionSocketAddress to 1UL), guardian.getSnapshot())
        verify(exactly = 0) { eventPublisher.publish(any()) }
    }

    @Test
    fun `should ban when node deviate from single voter`() {
        // given
        every { guardianProperties.minimalMedian } returns minimalMedian
        every { guardianProperties.toleranceMultiplier } returns toleranceMultiplier

        val voteNode = createNode(AttoAmount.MAX)
        val voterEvent = NodeConnected(randomSocketAddress(), voteNode)
        guardian.add(voterEvent)
        guardian.count(inboundMessage(voterEvent.node.publicUri, voterEvent.connectionSocketAddress))

        val normalPeer = createNode(AttoAmount.MAX)
        val normalEvent = NodeConnected(randomSocketAddress(), normalPeer)
        guardian.add(normalEvent)
        for (i in 0UL..toleranceMultiplier) {
            guardian.count(inboundMessage(normalEvent.node.publicUri, normalEvent.connectionSocketAddress))
        }

        every { voteWeighter.isAboveMinimalRebroadcastWeight(voterEvent.node.publicKey) } returns true

        // when
        guardian.guard()

        // then
        verify { eventPublisher.publish(NodeBanned(normalEvent.connectionSocketAddress.address)) }
    }

    @Test
    fun `should ban when node deviate from voters median`() {
        // given
        every { guardianProperties.minimalMedian } returns minimalMedian
        every { guardianProperties.toleranceMultiplier } returns toleranceMultiplier

        val votePeer1 = createNode(AttoAmount(ULong.MAX_VALUE / 2U))
        val voterEvent1 = NodeConnected(randomSocketAddress(), votePeer1)
        guardian.add(voterEvent1)
        guardian.count(inboundMessage(voterEvent1.node.publicUri, voterEvent1.connectionSocketAddress))

        val votePeer2 = createNode(AttoAmount(ULong.MAX_VALUE / 2U))
        val voterEvent2 = NodeConnected(randomSocketAddress(), votePeer2)
        guardian.add(voterEvent2)
        guardian.count(inboundMessage(voterEvent2.node.publicUri, voterEvent2.connectionSocketAddress))

        val normalPeer = createNode(AttoAmount.MAX)
        val normalEvent = NodeConnected(randomSocketAddress(), normalPeer)
        guardian.add(normalEvent)
        for (i in 0UL..toleranceMultiplier) {
            guardian.count(inboundMessage(normalEvent.node.publicUri, normalEvent.connectionSocketAddress))
        }

        every { voteWeighter.isAboveMinimalRebroadcastWeight(voterEvent1.node.publicKey) } returns true
        every { voteWeighter.isAboveMinimalRebroadcastWeight(voterEvent1.node.publicKey) } returns true

        // when
        guardian.guard()

        // then
        verify { eventPublisher.publish(NodeBanned(normalEvent.connectionSocketAddress.address)) }
    }

    @Test
    fun `should remove voter when disconnected`() {
        // given
        val node = createNode(AttoAmount.MAX)
        val event = NodeConnected(randomSocketAddress(), node)
        guardian.add(event)

        // when
        guardian.remove(NodeDisconnected(event.connectionSocketAddress, node))

        // then
        assertThat(guardian.getVoters()).isEmpty()
    }

    private fun createNode(weight: AttoAmount): AttoNode {
        val publicUri = randomURI()
        val node = createNode(publicUri)
        every { voteWeighter.get(node.publicKey) } returns weight
        return node
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
