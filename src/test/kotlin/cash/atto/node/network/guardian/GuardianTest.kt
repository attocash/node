package cash.atto.node.network.guardian

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toAttoHeight
import cash.atto.node.EventPublisher
import cash.atto.node.network.ConnectedPeer
import cash.atto.node.network.ConnectedPeers
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.MutableConnectedPeers
import cash.atto.node.network.NodeBanned
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionStreamRequest
import cash.atto.protocol.AttoVoteStreamRequest
import cash.atto.protocol.NodeFeature
import io.mockk.every
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
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.random.Random

@ExtendWith(MockKExtension::class)
class GuardianTest {
    private val minimalMedian = 1UL
    private val toleranceMultiplier = 10UL

    @RelaxedMockK
    lateinit var thisNode: AttoNode

    @RelaxedMockK
    lateinit var voteWeighter: VoteWeighter

    @RelaxedMockK
    lateinit var eventPublisher: EventPublisher

    @RelaxedMockK
    lateinit var guardianProperties: GuardianProperties

    @RelaxedMockK
    lateinit var clock: Clock

    @RelaxedMockK
    lateinit var connectedPeers: ConnectedPeers

    private val peers = MutableConnectedPeers()

    private var now = Instant.parse("2026-11-07T00:00:00Z")

    private lateinit var expectedResponses: ExpectedResponses
    private lateinit var peerBans: PeerBans
    private lateinit var guardian: Guardian

    @BeforeEach
    fun beforeEach() {
        every { guardianProperties.minimalMedian } returns minimalMedian
        every { guardianProperties.toleranceMultiplier } returns toleranceMultiplier
        every { guardianProperties.connectionRequestLimitPerMinute } returns 60
        every { clock.instant() } answers { now }
        peers.clear()
        every { connectedPeers.snapshot() } answers { peers.snapshot() }
        expectedResponses = ExpectedResponses(clock)
        peerBans = PeerBans(eventPublisher, clock)
        guardian = Guardian(thisNode, voteWeighter, guardianProperties, peerBans, expectedResponses, connectedPeers)
    }

    @Test
    fun `should take snapshot`() {
        val node = createNode(AttoAmount.MAX)
        val event = ConnectedPeer(node, randomSocketAddress())
        peers.connect(event)
        guardian.count(inboundMessage(node.publicUri, event.socketAddress))

        // when
        guardian.guard()

        // then
        assertEquals(mapOf(event.socketAddress to 1UL), guardian.getSnapshot())
        verify(exactly = 0) { eventPublisher.publish(any()) }
    }

    @Test
    fun `should ban when node deviate from single voter`() {
        val voteNode = createNode(AttoAmount.MAX)
        val voterEvent = ConnectedPeer(voteNode, randomSocketAddress())
        peers.connect(voterEvent)
        guardian.count(inboundMessage(voterEvent.node.publicUri, voterEvent.socketAddress))

        val normalPeer = createNode(AttoAmount.MAX)
        val normalEvent = ConnectedPeer(normalPeer, randomSocketAddress())
        peers.connect(normalEvent)
        for (i in 0UL..toleranceMultiplier) {
            guardian.count(inboundMessage(normalEvent.node.publicUri, normalEvent.socketAddress))
        }

        every { voteWeighter.isAboveMinimalRebroadcastWeight(voterEvent.node.publicKey) } returns true

        // when
        guardian.guard()

        // then
        verify {
            eventPublisher.publish(
                match { event ->
                    event as NodeBanned
                    event.address == normalEvent.socketAddress.address
                },
            )
        }
    }

    @Test
    fun `guard rejects banned addresses before dispatching their notification`() {
        // Given
        val voter = createNode(AttoAmount.MAX)
        val voterSocket = InetSocketAddress(InetAddress.getByName("8.8.8.8"), 10_000)
        peers.connect(ConnectedPeer(voter, voterSocket))
        every { voteWeighter.isAboveMinimalRebroadcastWeight(voter.publicKey) } returns true
        guardian.count(inboundMessage(voter.publicUri, voterSocket))
        val peer = createNode(AttoAmount.MAX)
        val peerSocket = InetSocketAddress(InetAddress.getByName("1.1.1.1"), 10_001)
        repeat(11) { guardian.count(inboundMessage(peer.publicUri, peerSocket)) }
        every { eventPublisher.publish(any()) } answers {
            val event = firstArg<NodeBanned>()
            assertEquals(InboundConnectionDecision.Banned, guardian.requestInboundConnection(event.address))
        }

        // When
        guardian.guard()

        // Then
        assertEquals(setOf(peerSocket.address), peerBans.snapshot())
        verify(exactly = 1) { eventPublisher.publish(match { it is NodeBanned && it.address == peerSocket.address }) }
    }

    @Test
    fun `should ban when node deviate from voters median`() {
        val votePeer1 = createNode(AttoAmount(ULong.MAX_VALUE / 2U))
        val voterEvent1 = ConnectedPeer(votePeer1, randomSocketAddress())
        peers.connect(voterEvent1)
        guardian.count(inboundMessage(voterEvent1.node.publicUri, voterEvent1.socketAddress))

        val votePeer2 = createNode(AttoAmount(ULong.MAX_VALUE / 2U))
        val voterEvent2 = ConnectedPeer(votePeer2, randomSocketAddress())
        peers.connect(voterEvent2)
        guardian.count(inboundMessage(voterEvent2.node.publicUri, voterEvent2.socketAddress))

        val normalPeer = createNode(AttoAmount.MAX)
        val normalEvent = ConnectedPeer(normalPeer, randomSocketAddress())
        peers.connect(normalEvent)
        for (i in 0UL..toleranceMultiplier) {
            guardian.count(inboundMessage(normalEvent.node.publicUri, normalEvent.socketAddress))
        }

        every { voteWeighter.isAboveMinimalRebroadcastWeight(voterEvent1.node.publicKey) } returns true
        every { voteWeighter.isAboveMinimalRebroadcastWeight(voterEvent1.node.publicKey) } returns true

        // when
        guardian.guard()

        // then
        verify {
            eventPublisher.publish(
                match { event ->
                    event as NodeBanned
                    event.address == normalEvent.socketAddress.address
                },
            )
        }
    }

    @Test
    fun `should remove voter when disconnected`() {
        // given
        val node = createNode(AttoAmount.MAX)
        val event = ConnectedPeer(node, randomSocketAddress())
        peers.connect(event)

        // when
        peers.disconnect(node.publicUri)

        // then
        assertThat(guardian.getVoters()).isEmpty()
    }

    @Test
    fun `voter query follows current connections and excludes non voters`() {
        // Given
        val old = createNode(AttoAmount.MAX)
        peers.connect(old, randomSocketAddress())
        val voter = createNode(AttoAmount.MAX)
        val voterSocket = randomSocketAddress()
        peers.connect(voter, voterSocket)

        // When
        peers.connect(old.copy(features = setOf(NodeFeature.HISTORICAL)), randomSocketAddress())

        // Then
        assertEquals(mapOf(voterSocket to voter.publicKey), guardian.getVoters())
    }

    @Test
    fun `should accept inbound connection under limit and record statistics`() {
        val address = InetAddress.getByName("8.8.8.8")

        val decision = guardian.requestInboundConnection(address)
        guardian.guard()

        assertEquals(InboundConnectionDecision.Accepted, decision)
        assertEquals(mapOf(InetSocketAddress(address, 0) to 1UL), guardian.getSnapshot())
    }

    @Test
    fun `should rate limit inbound connection over limit without banning peer`() {
        every { guardianProperties.connectionRequestLimitPerMinute } returns 1
        val address = InetAddress.getByName("8.8.4.4")

        val firstDecision = guardian.requestInboundConnection(address)
        val secondDecision = guardian.requestInboundConnection(address)
        guardian.guard()

        assertEquals(InboundConnectionDecision.Accepted, firstDecision)
        assertEquals(InboundConnectionDecision.RateLimited, secondDecision)
        assertEquals(mapOf(InetSocketAddress(address, 0) to 2UL), guardian.getSnapshot())
        verify(exactly = 0) { eventPublisher.publish(match { it is NodeBanned }) }
    }

    @Test
    fun `should reject banned inbound connection without recording statistics`() {
        // Given
        val address = InetAddress.getByName("1.1.1.1")
        peerBans.ban(address)

        // When
        val decision = guardian.requestInboundConnection(address)
        guardian.guard()

        // Then
        assertEquals(InboundConnectionDecision.Banned, decision)
        assertThat(guardian.getSnapshot()).isEmpty()

        // When
        now = now.plusSeconds(3_600)
        peerBans.review()
        val afterExpiry = guardian.requestInboundConnection(address)

        // Then
        assertEquals(InboundConnectionDecision.Accepted, afterExpiry)
    }

    @Test
    fun `should discard completed statistics after an idle window`() {
        // Given
        val node = createNode(AttoAmount.MAX)
        val socket = randomSocketAddress()
        guardian.count(inboundMessage(node.publicUri, socket))
        guardian.requestInboundConnection(socket.address)
        guardian.guard()
        assertEquals(mapOf(socket to 1UL, InetSocketAddress(socket.address, 0) to 1UL), guardian.getSnapshot())

        // When
        guardian.guard()

        // Then
        assertThat(guardian.getSnapshot()).isEmpty()
    }

    @Test
    fun `should include idle voters in successive window medians`() {
        // Given
        val voters = (1..3).map { ConnectedPeer(createNode(AttoAmount.MAX), randomSocketAddress()) }
        voters.forEach {
            peers.connect(it)
            every { voteWeighter.isAboveMinimalRebroadcastWeight(it.node.publicKey) } returns true
            guardian.count(inboundMessage(it.node.publicUri, it.socketAddress))
        }
        guardian.guard()
        val active = voters.first()

        // When
        repeat(2) {
            repeat(100) {
                guardian.count(inboundMessage(active.node.publicUri, active.socketAddress))
            }
            guardian.guard()
        }

        // Then
        assertEquals(mapOf(active.socketAddress to 100UL), guardian.getSnapshot())
        verify(exactly = 0) { eventPublisher.publish(match { it is NodeBanned }) }
    }

    @Test
    fun `should release departed socket statistics across reconnects`() {
        // Given
        val node = createNode(AttoAmount.MAX)
        val address = InetAddress.getByName("8.8.8.8")

        // When
        repeat(20) { index ->
            val socket = InetSocketAddress(address, 10_000 + index)
            peers.connect(ConnectedPeer(node, socket))
            guardian.count(inboundMessage(node.publicUri, socket))
            peers.disconnect(node.publicUri)
            guardian.guard()
            assertEquals(mapOf(socket to 1UL), guardian.getSnapshot())
        }
        guardian.guard()

        // Then
        assertThat(guardian.getSnapshot()).isEmpty()
        assertThat(guardian.getVoters()).isEmpty()
    }

    @Test
    fun `should count every concurrent message in exactly one window`() {
        // Given
        val node = createNode(AttoAmount.MAX)
        val message = inboundMessage(node.publicUri, randomSocketAddress())
        val start = CountDownLatch(1)

        // When
        val total =
            Executors.newFixedThreadPool(5).use { executor ->
                val counters =
                    (1..4).map {
                        executor.submit {
                            start.await()
                            repeat(500) { guardian.count(message) }
                        }
                    }
                val windows =
                    executor.submit<ULong> {
                        start.await()
                        var total = 0UL
                        repeat(200) {
                            guardian.guard()
                            total += guardian.getSnapshot().values.sum()
                        }
                        total
                    }
                start.countDown()
                counters.forEach { it.get() }
                val completed = windows.get()
                guardian.guard()
                completed + guardian.getSnapshot().values.sum()
            }

        // Then
        assertEquals(2_000UL, total)
    }

    @Test
    fun `should exempt final expected response and immediately remove its credit`() {
        // Given
        val node = createNode(AttoAmount.MAX)
        val message = inboundMessage(node.publicUri, randomSocketAddress())
        expectedResponses.record(request(node, 2UL))

        // When
        guardian.count(message)
        guardian.count(message)
        guardian.guard()

        // Then
        assertEquals(0, expectedResponses.peerCount)
        assertThat(guardian.getSnapshot()).isEmpty()
        guardian.count(message)
        guardian.guard()
        assertEquals(mapOf(message.socketAddress to 1UL), guardian.getSnapshot())
    }

    @Test
    fun `should preserve credits across reconnect and expire unanswered credit`() {
        // Given
        val node = createNode(AttoAmount.MAX)
        val oldSocket = InetSocketAddress(InetAddress.getLoopbackAddress(), 10_000)
        val newSocket = InetSocketAddress(InetAddress.getLoopbackAddress(), 10_001)
        peers.connect(ConnectedPeer(node, oldSocket))
        peers.disconnect(node.publicUri)
        now = now.plusSeconds(1)
        peers.connect(ConnectedPeer(node, newSocket))
        expectedResponses.record(request(node, 2UL))

        // When
        guardian.count(inboundMessage(node.publicUri, newSocket))
        guardian.guard()

        // Then
        assertEquals(mapOf(newSocket to node.publicKey), guardian.getVoters())
        assertEquals(1, expectedResponses.peerCount)
        assertThat(guardian.getSnapshot()).isEmpty()
        now = now.plus(AttoTransactionStreamRequest.TIMEOUT)
        guardian.guard()
        assertEquals(0, expectedResponses.peerCount)
        guardian.count(inboundMessage(node.publicUri, newSocket))
        guardian.guard()
        assertEquals(mapOf(newSocket to 1UL), guardian.getSnapshot())
    }

    @Test
    fun `should expire late request credits against their original timestamp`() {
        // Given
        val node = createNode(AttoAmount.MAX)
        val request = request(node, 2UL)
        peers.disconnect(node.publicUri)
        now = now.plusSeconds(30)

        // When
        expectedResponses.record(request)
        assertEquals(1, expectedResponses.peerCount)
        now = now.plusSeconds(30)
        guardian.guard()

        // Then
        assertEquals(0, expectedResponses.peerCount)
    }

    @Test
    fun `should ignore already expired outbound requests`() {
        // Given
        val node = createNode(AttoAmount.MAX)
        val request = request(node, 2UL)
        now = now.plus(AttoTransactionStreamRequest.TIMEOUT)

        // When
        expectedResponses.record(request)

        // Then
        assertEquals(0, expectedResponses.peerCount)
    }

    @Test
    fun `should expire remaining credits without extending them on response`() {
        // Given
        val node = createNode(AttoAmount.MAX)
        val message = inboundMessage(node.publicUri, randomSocketAddress())
        expectedResponses.record(request(node, 2UL))
        now = now.plusSeconds(59)
        guardian.count(message)

        // When
        now = now.plusSeconds(1)
        guardian.count(message)
        guardian.guard()

        // Then
        assertEquals(0, expectedResponses.peerCount)
        assertEquals(mapOf(message.socketAddress to 1UL), guardian.getSnapshot())
    }

    @Test
    fun `should preserve latest request expiry when outbound events arrive out of order`() {
        // Given
        val node = createNode(AttoAmount.MAX)
        val older = request(node, 2UL)
        now = now.plusSeconds(30)
        expectedResponses.record(request(node, 2UL))
        expectedResponses.record(older)
        val message = inboundMessage(node.publicUri, randomSocketAddress())

        // When
        now = now.plusSeconds(30)
        guardian.count(message)
        guardian.guard()

        // Then
        assertEquals(1, expectedResponses.peerCount)
        assertThat(guardian.getSnapshot()).isEmpty()
        now = now.plusSeconds(30)
        guardian.guard()
        assertEquals(0, expectedResponses.peerCount)
    }

    @Test
    fun `should consume expected credits atomically under concurrent responses`() {
        // Given
        val node = createNode(AttoAmount.MAX)
        val message = inboundMessage(node.publicUri, randomSocketAddress())
        expectedResponses.record(request(node, 200UL))
        val start = CountDownLatch(1)

        // When
        Executors.newFixedThreadPool(4).use { executor ->
            val counters =
                (1..4).map {
                    executor.submit {
                        start.await()
                        repeat(100) { guardian.count(message) }
                    }
                }
            start.countDown()
            counters.forEach { it.get() }
        }
        guardian.guard()

        // Then
        assertEquals(0, expectedResponses.peerCount)
        assertEquals(mapOf(message.socketAddress to 200UL), guardian.getSnapshot())
    }

    private fun request(
        node: AttoNode,
        count: ULong,
    ): DirectNetworkMessage<AttoTransactionStreamRequest> =
        DirectNetworkMessage(
            publicUri = node.publicUri,
            payload = AttoTransactionStreamRequest(node.publicKey, 1UL.toAttoHeight(), count.toAttoHeight()),
            expectedResponseCount = count,
            timestamp = now,
        )

    private fun createNode(weight: AttoAmount): AttoNode {
        val publicUri = randomURI()
        val node = createNode(publicUri)
        every { voteWeighter.get(node.publicKey) } returns weight
        return node
    }

    private fun createNode(publicUri: URI): AttoNode =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            publicUri = publicUri,
            features = setOf(NodeFeature.VOTING, NodeFeature.HISTORICAL),
        )

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
