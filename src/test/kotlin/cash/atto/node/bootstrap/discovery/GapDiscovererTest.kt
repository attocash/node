package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoTransaction
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.bootstrap.unchecked.GapView
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionRepository
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionStreamRequest
import cash.atto.protocol.AttoTransactionStreamResponse
import cash.atto.protocol.NodeFeature
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class GapDiscovererTest {
    @Test
    fun `no historical peer prevents the gap query`() =
        runTest {
            // given
            val fixture =
                fixture(
                    gaps = listOf(gap(publicKey(1), 1U, 1U, hash(1))),
                    connect = false,
                )

            // when
            fixture.discover()

            // then
            coVerify(exactly = 0) { fixture.repository.findGaps(any()) }
            assertTrue(fixture.requests.isEmpty())
        }

    @Test
    fun `adaptive target below the 250 transaction request budget prevents the gap query`() =
        runTest {
            // given
            val fixture =
                fixture(
                    gaps = listOf(gap(publicKey(1), 1U, 1U, hash(1))),
                    capacity = 250,
                    remainingCapacity = 249,
                )

            // when
            fixture.discover()

            // then
            coVerify(exactly = 0) { fixture.repository.findGaps(any()) }
            assertTrue(fixture.requests.isEmpty())
        }

    @Test
    fun `parallel peer starts one session for every queue sized slot`() =
        runTest {
            // given
            val gaps = (1..3).map { gap(publicKey(it.toByte()), 1U, 1U, hash(it.toByte())) }
            val fixture = fixture(gaps = gaps, capacity = 3_000)

            // when
            fixture.discover()
            fixture.discover()

            // then
            assertEquals(3, fixture.requests.size)
            assertEquals(3, fixture.discoverer.activeSessionCount())
            coVerify(exactly = 1) { fixture.repository.findGaps(3) }
        }

    @Test
    fun `legacy peer is limited to one active session`() =
        runTest {
            // given
            val gaps = (1..3).map { gap(publicKey(it.toByte()), 1U, 1U, hash(it.toByte())) }
            val fixture =
                fixture(
                    gaps = gaps,
                    capacity = 3_000,
                    nodes = listOf(node(100, protocolVersion = 0u)),
                )

            // when
            fixture.discover()

            // then
            assertEquals(1, fixture.requests.size)
            assertEquals(1, fixture.discoverer.activeSessionCount())
            coVerify(exactly = 1) { fixture.repository.findGaps(1) }
        }

    @Test
    fun `legacy peers each accept one active session`() =
        runTest {
            // given
            val gaps = (1..3).map { gap(publicKey(it.toByte()), 1U, 1U, hash(it.toByte())) }
            val nodes = (100..102).map { node(it.toByte(), protocolVersion = 0u) }
            val fixture = fixture(gaps = gaps, capacity = 3_000, nodes = nodes)

            // when
            fixture.discover()

            // then
            assertEquals(3, fixture.requests.size)
            assertEquals(nodes.map(AttoNode::publicUri).toSet(), fixture.requests.map { it.publicUri }.toSet())
        }

    @Test
    fun `active accounts are skipped while filling new session slots`() =
        runTest {
            // given
            val firstGap = gap(publicKey(1), 1U, 1U, hash(1))
            val secondGap = gap(publicKey(2), 1U, 1U, hash(2))
            val fixture = fixture(gaps = emptyList(), capacity = 2_000)
            coEvery { fixture.repository.findGaps(2) } returnsMany
                listOf(
                    listOf(firstGap).asFlow(),
                    listOf(firstGap, secondGap).asFlow(),
                )

            // when
            fixture.discover()
            fixture.discover()

            // then
            assertEquals(
                listOf(firstGap.publicKey, secondGap.publicKey),
                fixture.requests.map { it.payload.publicKey },
            )
            assertEquals(2, fixture.discoverer.activeSessionCount())
        }

    @Test
    fun `request range is capped to the configured capacity budget`() =
        runTest {
            // given
            val fixture =
                fixture(
                    gaps = listOf(gap(publicKey(1), 1U, 100U, hash(100))),
                    capacity = 25,
                )

            // when
            fixture.discover()

            // then
            val request = fixture.requests.single()
            assertEquals(25UL, request.expectedResponseCount)
            assertEquals(76U.toAttoHeight(), request.payload.startHeight)
            assertEquals(100U.toAttoHeight(), request.payload.endHeight)
        }

    @Test
    fun `protocol maximum caps the request when target capacity is larger`() =
        runTest {
            // given
            val fixture =
                fixture(
                    gaps = listOf(gap(publicKey(1), 1U, 1_500U, hash(1))),
                )

            // when
            fixture.discover()

            // then
            val request = fixture.requests.single()
            assertEquals(1_000UL, request.expectedResponseCount)
            assertEquals(501U.toAttoHeight(), request.payload.startHeight)
            assertEquals(1_500U.toAttoHeight(), request.payload.endHeight)
        }

    @Test
    fun `peer disconnect releases all its sessions and allows retry`() =
        runTest {
            // given
            val gaps = (1..3).map { gap(publicKey(it.toByte()), 1U, 2U, hash(it.toByte())) }
            val fixture = fixture(gaps = gaps, capacity = 3_000)
            fixture.discover()

            // when
            fixture.disconnect()

            // then
            assertEquals(0, fixture.discoverer.activeSessionCount())

            // when
            fixture.connect()
            fixture.discover()

            // then
            assertEquals(6, fixture.requests.size)
            assertEquals(3, fixture.discoverer.activeSessionCount())
        }

    @Test
    fun `caffeine expires sessions after one minute without progress`() =
        runTest {
            // given
            val clock = MutableClock()
            val gaps = (1..2).map { gap(publicKey(it.toByte()), 1U, 2U, hash(it.toByte())) }
            val fixture = fixture(gaps = gaps, capacity = 2_000, clock = clock)
            fixture.discover()

            // when
            clock.advance(Duration.ofSeconds(61))
            fixture.discover()

            // then
            assertEquals(4, fixture.requests.size)
            assertEquals(2, fixture.discoverer.activeSessionCount())
            coVerify(exactly = 2) { fixture.repository.findGaps(2) }
        }

    @Test
    fun `buffered response refreshes the session expiration`() =
        runTest {
            // Given
            val clock = MutableClock()
            val publicKey = publicKey(1)
            val first = transaction(publicKey, 1U, hash(0), 1)
            val second = transaction(publicKey, 2U, first.hash, 2)
            val fixture =
                fixture(
                    gaps = listOf(gap(publicKey, 1U, 2U, second.hash)),
                    capacity = 1_000,
                    clock = clock,
                )
            fixture.discover()
            clock.advance(Duration.ofSeconds(59))

            // When
            fixture.discoverer.process(fixture.response(first))
            clock.advance(Duration.ofSeconds(2))

            // Then
            assertEquals(1, fixture.discoverer.activeSessionCount())

            // When
            clock.advance(Duration.ofSeconds(59))

            // Then
            assertEquals(0, fixture.discoverer.activeSessionCount())
        }

    @Test
    fun `irrelevant traffic does not refresh the session expiration`() =
        runTest {
            // Given
            val clock = MutableClock()
            val publicKey = publicKey(1)
            val transaction = transaction(publicKey, 1U, hash(0), 1)
            val fixture =
                fixture(
                    gaps = listOf(gap(publicKey, 1U, 1U, transaction.hash)),
                    capacity = 1_000,
                    clock = clock,
                )
            fixture.discover()
            clock.advance(Duration.ofSeconds(59))

            // When
            fixture.discoverer.process(
                fixture.response(transaction).copy(publicUri = URI("ws://127.0.0.1:9999")),
            )
            clock.advance(Duration.ofSeconds(2))

            // Then
            assertEquals(0, fixture.discoverer.activeSessionCount())
        }

    @Test
    fun `publication failure releases the session`() =
        runTest {
            // given
            val fixture =
                fixture(
                    gaps = listOf(gap(publicKey(1), 1U, 1U, hash(1))),
                    capacity = 1_000,
                    publicationFailures = 1,
                )

            // when
            try {
                fixture.discover()
                fail("Expected request publication to fail")
            } catch (_: IllegalStateException) {
                // expected
            }

            // then
            assertEquals(0, fixture.discoverer.activeSessionCount())

            // when
            fixture.discover()

            // then
            assertEquals(1, fixture.requests.size)
            assertEquals(1, fixture.discoverer.activeSessionCount())
        }

    @Test
    fun `cache clearing releases all active sessions`() =
        runTest {
            // given
            val gaps = (1..3).map { gap(publicKey(it.toByte()), 1U, 1U, hash(it.toByte())) }
            val fixture = fixture(gaps = gaps, capacity = 3_000)
            fixture.discover()

            // when
            fixture.discoverer.clear()

            // then
            assertEquals(0, fixture.discoverer.activeSessionCount())
        }

    @Test
    fun `empty query executes every time discovery is called`() =
        runTest {
            // given
            val fixture = fixture(gaps = emptyList())

            // when
            fixture.discover()
            fixture.discover()

            // then
            coVerify(exactly = 2) { fixture.repository.findGaps(any()) }
        }

    private fun fixture(
        gaps: List<GapView>,
        capacity: Int = 10_000,
        remainingCapacity: Int = capacity,
        publicationFailures: Int = 0,
        connect: Boolean = true,
        clock: Clock = MutableClock(),
        nodes: List<AttoNode> = listOf(node(100)),
    ): Fixture {
        val properties =
            DiscoveryProperties().apply {
                this.capacity = capacity
                batchSize = minOf(capacity, 1_000)
            }
        val availableCapacity = AtomicInteger(remainingCapacity)
        val queue = mockk<DiscoveryQueue>()
        every { queue.remainingTargetCapacity() } answers { availableCapacity.get() }
        coEvery { queue.queue(any(), DiscoverySource.GAP) } returns true

        val repository = mockk<UncheckedTransactionRepository>()
        coEvery { repository.findGaps(any()) } answers { gaps.asFlow() }

        val requests = mutableListOf<DirectNetworkMessage<AttoTransactionStreamRequest>>()
        var publicationAttempts = 0
        val publisher = mockk<NetworkMessagePublisher>()
        every { publisher.publish(any()) } answers {
            publicationAttempts++
            if (publicationAttempts <= publicationFailures) {
                error("simulated publication failure")
            }
            requests += firstArg<DirectNetworkMessage<AttoTransactionStreamRequest>>()
        }

        val discoverer =
            GapDiscoverer(
                uncheckedTransactionRepository = repository,
                networkMessagePublisher = publisher,
                discoveryQueue = queue,
                discoveryProperties = properties,
                meterRegistry = SimpleMeterRegistry(),
                clock = clock,
            )
        return Fixture(
            discoverer = discoverer,
            queue = queue,
            repository = repository,
            requests = requests,
            nodes = nodes,
        ).also {
            if (connect) {
                it.connect()
            }
        }
    }

    private data class Fixture(
        val discoverer: GapDiscoverer,
        val queue: DiscoveryQueue,
        val repository: UncheckedTransactionRepository,
        val requests: List<DirectNetworkMessage<AttoTransactionStreamRequest>>,
        val nodes: List<AttoNode>,
    ) {
        suspend fun discover(): Int = discoverer.discover()

        fun connect() {
            nodes.forEach { node ->
                discoverer.add(NodeConnected(socketAddress(node), node))
            }
        }

        fun disconnect(node: AttoNode = nodes.first()) {
            discoverer.remove(NodeDisconnected(socketAddress(node), node))
        }

        fun response(transaction: AttoTransaction): InboundNetworkMessage<AttoTransactionStreamResponse> {
            val request =
                requests.last { it.payload.publicKey == transaction.block.publicKey }
            return InboundNetworkMessage(
                source = MessageSource.WEBSOCKET,
                publicUri = request.publicUri,
                socketAddress = InetSocketAddress(request.publicUri.host, request.publicUri.port),
                payload = AttoTransactionStreamResponse(transaction),
            )
        }

        private fun socketAddress(node: AttoNode): InetSocketAddress = InetSocketAddress(node.publicUri.host, node.publicUri.port)
    }

    private class MutableClock(
        private var now: Instant = Instant.parse("2026-08-04T00:00:00Z"),
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    private companion object {
        fun gap(
            publicKey: AttoPublicKey,
            startHeight: UInt,
            endHeight: UInt,
            expectedEndHash: AttoHash,
        ): GapView =
            GapView(
                publicKey = publicKey,
                startHeight = startHeight.toAttoHeight(),
                endHeight = endHeight.toAttoHeight(),
                expectedEndHash = expectedEndHash,
            )

        fun node(
            marker: Byte,
            protocolVersion: UShort = AttoNode.CURRENT_PROTOCOL_VERSION,
        ): AttoNode =
            AttoNode(
                network = AttoNetwork.LOCAL,
                protocolVersion = protocolVersion,
                algorithm = AttoAlgorithm.V1,
                publicKey = publicKey(marker),
                publicUri = URI("ws://127.0.0.1:${8_000 + marker}"),
                features = setOf(NodeFeature.HISTORICAL),
            )

        fun transaction(
            publicKey: AttoPublicKey,
            height: UInt,
            previous: AttoHash,
            marker: Byte,
        ): AttoTransaction {
            val block =
                AttoSendBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = publicKey,
                    height = height.toAttoHeight(),
                    balance = AttoAmount.MAX,
                    timestamp = AttoInstant.now(),
                    previous = previous,
                    receiverAlgorithm = AttoAlgorithm.V1,
                    receiverPublicKey = publicKey((marker + 20).toByte()),
                    amount = AttoAmount.MAX,
                )
            return AttoTransaction(
                block = block,
                signature = AttoSignature(ByteArray(64) { (marker + 1).toByte() }),
                work = AttoWork(ByteArray(8) { (marker + 2).toByte() }),
            )
        }

        fun publicKey(marker: Byte): AttoPublicKey = AttoPublicKey(ByteArray(32) { marker })

        fun hash(marker: Byte): AttoHash = AttoHash(ByteArray(32) { marker })
    }
}
