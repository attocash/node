package cash.atto.node.network

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.node.Event
import cash.atto.node.EventPublisher
import cash.atto.protocol.AttoKeepAlive
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import com.github.benmanes.caffeine.cache.Ticker
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

class NodeConnectionManagerTest {
    @Test
    fun `rejects unique session beyond fixed capacity`() =
        runTest {
            // given
            val fixture = fixture()
            val pool = fixture.fill(NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS)

            // when
            val rejectedSession = TestWebSocketSession()
            val rejectedNode = sampleNode(NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS)
            val rejectedJob = pool.launchManage(fixture, rejectedNode, rejectedSession)
            awaitCondition { rejectedSession.closeReason != null }

            // then
            assertEquals(NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS, fixture.manager.connectionCount)
            assertEquals(NodeConnectionManager.CAPACITY_CLOSE_REASON, rejectedSession.closeReason)
            assertFalse(fixture.manager.isConnected(rejectedNode.publicUri))
            verify(exactly = NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS) {
                fixture.eventPublisher.publish(match { it is NodeConnected })
            }

            fixture.manager.clear()
            (pool.jobs + rejectedJob).joinAll()
            pool.close()
        }

    @Test
    fun `duplicate at capacity preserves original mapping without replacement events`() =
        runTest {
            // given
            val fixture = fixture()
            val pool = fixture.fill(NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS)
            val node = pool.nodes.first()
            val activeSession = pool.sessions.first()
            fixture.events.clear()

            // when
            val duplicateSession = TestWebSocketSession()
            val duplicateJob = pool.launchManage(fixture, node, duplicateSession)
            awaitCondition { duplicateSession.closeReason != null }

            // then
            assertEquals(NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS, fixture.manager.connectionCount)
            assertTrue(fixture.manager.isConnected(node.publicUri))
            assertEquals(NodeConnectionManager.DUPLICATE_CLOSE_REASON, duplicateSession.closeReason)
            assertEquals(1, duplicateSession.closeCount)
            assertEquals(null, activeSession.closeReason)
            assertTrue(fixture.events.isEmpty())

            fixture.manager.clear()
            (pool.jobs + duplicateJob).joinAll()
            pool.close()
        }

    @Test
    fun `concurrent admission never exceeds fixed capacity`() =
        runTest {
            // given
            val fixture = fixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gate = CompletableDeferred<Unit>()
            val sessions = List(NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS + 50) { TestWebSocketSession() }
            val observing = AtomicBoolean(true)
            val maximumObserved = AtomicInteger()
            val observer =
                scope.launch {
                    while (observing.get()) {
                        maximumObserved.accumulateAndGet(fixture.manager.admittedSessionCount, ::maxOf)
                    }
                }
            val jobs =
                sessions.mapIndexed { index, session ->
                    scope.launch {
                        gate.await()
                        fixture.manage(sampleNode(index), socketAddress(index), session)
                    }
                }

            // when
            gate.complete(Unit)
            awaitCondition {
                fixture.manager.connectionCount == NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS &&
                    sessions.count { it.closeReason == NodeConnectionManager.CAPACITY_CLOSE_REASON } == 50
            }
            observing.set(false)
            observer.join()

            // then
            assertEquals(NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS, fixture.manager.connectionCount)
            assertTrue(maximumObserved.get() <= NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS)
            assertEquals(50, sessions.count { it.closeReason == NodeConnectionManager.CAPACITY_CLOSE_REASON })

            fixture.manager.clear()
            jobs.joinAll()
            scope.cancel()
        }

    @Test
    fun `disconnect invalid input ban and clear release only matching slots`() =
        runTest {
            // given
            val fixture = fixture()
            val pool = fixture.fill(NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS)
            val completedNode = pool.nodes[0]
            val invalidNode = pool.nodes[1]
            val bannedNode = pool.nodes[2]
            val failedNode = pool.nodes[3]

            // when
            pool.sessions[0].completeIncoming()
            awaitCondition { fixture.manager.connectionCount == NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS - 1 }
            val replacementNode = sampleNode(NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS + 1)
            val replacementSession = TestWebSocketSession()
            val replacementJob = pool.launchManage(fixture, replacementNode, replacementSession)
            awaitCondition { fixture.manager.connectionCount == NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS }

            pool.sessions[1].sendIncoming(Frame.Binary(true, byteArrayOf(0)))
            awaitCondition { !fixture.manager.isConnected(invalidNode.publicUri) }
            fixture.manager.ban(NodeBanned(socketAddress(2).address))
            awaitCondition { !fixture.manager.isConnected(bannedNode.publicUri) }
            pool.sessions[3].failIncoming(IllegalStateException("incoming failed"))
            awaitCondition { !fixture.manager.isConnected(failedNode.publicUri) }

            // then
            assertFalse(fixture.manager.isConnected(completedNode.publicUri))
            assertTrue(fixture.manager.isConnected(replacementNode.publicUri))
            assertEquals(NodeConnectionManager.MAX_ACTIVE_PEER_SESSIONS - 3, fixture.manager.connectionCount)
            assertEquals(fixture.manager.connectionCount, fixture.manager.connectedPublicKeys.size)
            awaitCondition {
                runCatching {
                    verify(exactly = 1) {
                        fixture.eventPublisher.publish(
                            match { it is NodeDisconnected && it.node.publicUri == completedNode.publicUri },
                        )
                    }
                }.isSuccess
            }

            // when
            fixture.manager.clear()

            // then
            assertEquals(0, fixture.manager.connectionCount)
            (pool.jobs + replacementJob).joinAll()
            pool.close()
        }

    @Test
    fun `expiry releases slot with deterministic ticker`() =
        runTest {
            // given
            val ticker = MutableTicker()
            val fixture = fixture(Duration.ofSeconds(1), ticker)
            val pool = fixture.fill(1)
            val node = pool.nodes.single()

            // when
            ticker.advance(Duration.ofMillis(750))
            pool.sessions.single().sendIncoming(
                Frame.Binary(true, NetworkSerializer.serialize(AttoKeepAlive(node.publicUri))),
            )
            awaitCondition {
                runCatching {
                    verify(exactly = 1) {
                        fixture.messagePublisher.publish(
                            match { it is InboundNetworkMessage<*> && it.payload is AttoKeepAlive },
                        )
                    }
                }.isSuccess
            }
            ticker.advance(Duration.ofMillis(750))
            fixture.manager.cleanUpExpiredConnections()

            // then
            assertEquals(1, fixture.manager.connectionCount)

            // when
            ticker.advance(Duration.ofMillis(500))
            fixture.manager.cleanUpExpiredConnections()

            // then
            assertEquals(0, fixture.manager.connectionCount)
            assertFalse(fixture.manager.isConnected(node.publicUri))
            awaitCondition {
                runCatching {
                    verify(exactly = 1) {
                        fixture.eventPublisher.publish(
                            match { it is NodeDisconnected && it.node.publicUri == node.publicUri },
                        )
                    }
                }.isSuccess
            }
            pool.jobs.joinAll()
            pool.close()
        }

    @Test
    fun `keepalive from stale session does not refresh current connection`() =
        runTest {
            // given
            val ticker = MutableTicker()
            val fixture = fixture(Duration.ofSeconds(1), ticker)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val node = sampleNode(1)
            val staleSession = TestWebSocketSession(closeIncomingOnClose = false)
            val staleJob =
                scope.launch {
                    runCatching { fixture.manage(node, socketAddress(1), staleSession) }
                }
            awaitCondition { fixture.manager.isConnected(node.publicUri) }
            ticker.advance(Duration.ofSeconds(1))
            fixture.manager.cleanUpExpiredConnections()
            awaitCondition { !fixture.manager.isConnected(node.publicUri) && staleSession.closeCount == 1 }
            val currentSession = TestWebSocketSession()
            val currentJob =
                scope.launch {
                    runCatching { fixture.manage(node, socketAddress(2), currentSession) }
                }
            awaitCondition { fixture.manager.isConnected(node.publicUri) }
            ticker.advance(Duration.ofMillis(750))

            // when
            staleSession.sendIncoming(
                Frame.Binary(true, NetworkSerializer.serialize(AttoKeepAlive(node.publicUri))),
            )
            awaitCondition {
                runCatching {
                    verify(exactly = 1) {
                        fixture.messagePublisher.publish(
                            match { it is InboundNetworkMessage<*> && it.payload is AttoKeepAlive },
                        )
                    }
                }.isSuccess
            }
            ticker.advance(Duration.ofMillis(500))
            fixture.manager.cleanUpExpiredConnections()

            // then
            assertEquals(0, fixture.manager.connectionCount)
            assertFalse(fixture.manager.isConnected(node.publicUri))
            staleSession.completeIncoming()
            listOf(staleJob, currentJob).joinAll()
            scope.cancel()
        }

    @Test
    fun `stalled duplicate close does not block admission keepalive or expiry`() =
        runTest {
            // given
            val ticker = MutableTicker()
            val fixture = fixture(Duration.ofSeconds(1), ticker)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val closeGate = CompletableDeferred<Unit>()
            val node = sampleNode(1)
            val activeSession = TestWebSocketSession()
            val activeJob =
                scope.launch {
                    runCatching { fixture.manage(node, socketAddress(1), activeSession) }
                }
            awaitCondition { fixture.manager.isConnected(node.publicUri) }
            fixture.events.clear()

            // when
            val duplicateSession = TestWebSocketSession(closeGate = closeGate)
            val duplicateJob =
                scope.launch {
                    runCatching { fixture.manage(node, socketAddress(2), duplicateSession) }
                }
            awaitCondition { duplicateSession.closeStarted.isCompleted }

            // then
            assertFalse(closeGate.isCompleted)
            assertEquals(1, fixture.manager.connectionCount)
            assertEquals(NodeConnectionManager.DUPLICATE_CLOSE_REASON, duplicateSession.closeReason)
            assertEquals(null, activeSession.closeReason)
            assertTrue(fixture.events.isEmpty())

            // when
            val additionalNode = sampleNode(2)
            val additionalSession = TestWebSocketSession()
            val additionalJob =
                scope.launch {
                    runCatching { fixture.manage(additionalNode, socketAddress(3), additionalSession) }
                }
            awaitCondition { fixture.manager.connectionCount == 2 }
            ticker.advance(Duration.ofMillis(750))
            activeSession.sendIncoming(
                Frame.Binary(true, NetworkSerializer.serialize(AttoKeepAlive(node.publicUri))),
            )
            awaitCondition {
                runCatching {
                    verify(exactly = 1) {
                        fixture.messagePublisher.publish(
                            match { it is InboundNetworkMessage<*> && it.payload is AttoKeepAlive },
                        )
                    }
                }.isSuccess
            }
            ticker.advance(Duration.ofMillis(750))
            fixture.manager.cleanUpExpiredConnections()

            // then
            assertEquals(1, fixture.manager.connectionCount)
            assertTrue(fixture.manager.isConnected(node.publicUri))
            assertFalse(closeGate.isCompleted)

            // when
            ticker.advance(Duration.ofMillis(500))
            fixture.manager.cleanUpExpiredConnections()

            // then
            assertEquals(0, fixture.manager.connectionCount)
            assertFalse(closeGate.isCompleted)

            // when
            closeGate.complete(Unit)

            // then
            listOf(activeJob, duplicateJob, additionalJob).joinAll()
            assertEquals(1, duplicateSession.closeCount)
            scope.cancel()
        }

    @Test
    fun `limits pending sessions from one address and releases reservations idempotently`() {
        // given
        val fixture = fixture()
        val address = InetAddress.getByName("192.0.2.1")
        val reservations =
            (0 until 16).map { index ->
                fixture.manager.reserveInbound(sampleNode(index).publicUri, address).acceptedReservation()
            }

        // when
        val rejected = fixture.manager.reserveInbound(sampleNode(16).publicUri, address)

        // then
        rejected.assertRejected(PeerSessionAdmissionRejection.ADDRESS_CAPACITY)
        assertEquals(16, fixture.manager.pendingConnectionCount)
        assertEquals(
            16.0,
            fixture.meterRegistry
                .get("network.peers.pending")
                .gauge()
                .value(),
        )
        assertEquals(1.0, fixture.rejectionCount(PeerSessionAdmissionRejection.ADDRESS_CAPACITY))

        // when
        fixture.manager.release(reservations.first())
        fixture.manager.release(reservations.first())
        val replacement = fixture.manager.reserveInbound(sampleNode(16).publicUri, address)

        // then
        assertInstanceOf(PeerSessionReservationResult.Accepted::class.java, replacement)
        assertEquals(16, fixture.manager.pendingConnectionCount)
        fixture.manager.clear()
    }

    @Test
    fun `limits sessions across an IPv4 prefix`() {
        // given
        val fixture = fixture()
        val reservations =
            (1..64).map { suffix ->
                val address = InetAddress.getByAddress(byteArrayOf(198.toByte(), 51, 100, suffix.toByte()))
                fixture.manager.reserveInbound(sampleNode(suffix).publicUri, address).acceptedReservation()
            }

        // when
        val rejected =
            fixture.manager.reserveInbound(
                sampleNode(65).publicUri,
                InetAddress.getByAddress(byteArrayOf(198.toByte(), 51, 100, 65)),
            )

        // then
        rejected.assertRejected(PeerSessionAdmissionRejection.PREFIX_CAPACITY)
        assertEquals(64, reservations.size)
        fixture.manager.clear()
    }

    @Test
    fun `limits sessions across an IPv6 prefix`() {
        // given
        val fixture = fixture()
        val prefix = byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 1, 0, 2)
        val reservations =
            (1..64).map { suffix ->
                fixture.manager
                    .reserveInbound(sampleNode(suffix).publicUri, ipv6Address(prefix, suffix))
                    .acceptedReservation()
            }

        // when
        val rejected = fixture.manager.reserveInbound(sampleNode(65).publicUri, ipv6Address(prefix, 65))

        // then
        rejected.assertRejected(PeerSessionAdmissionRejection.PREFIX_CAPACITY)
        assertEquals(64, reservations.size)
        fixture.manager.clear()
    }

    @Test
    fun `counts IPv4-mapped IPv6 as the same address`() {
        // given
        val properties =
            NetworkProperties().apply {
                maxPeerSessionsPerAddress = 1
            }
        val fixture = fixture(properties = properties)
        val ipv4Bytes = byteArrayOf(203.toByte(), 0, 113, 8)
        fixture.manager.reserveInbound(sampleNode(1).publicUri, InetAddress.getByAddress(ipv4Bytes)).acceptedReservation()
        val mappedBytes =
            ByteArray(16).also {
                it[10] = 0xff.toByte()
                it[11] = 0xff.toByte()
                ipv4Bytes.copyInto(it, destinationOffset = 12)
            }
        val mappedAddress = Inet6Address.getByAddress(null, mappedBytes, -1)

        // when
        val rejected = fixture.manager.reserveInbound(sampleNode(2).publicUri, mappedAddress)

        // then
        rejected.assertRejected(PeerSessionAdmissionRejection.ADDRESS_CAPACITY)
        fixture.manager.clear()
    }

    @Test
    fun `first authenticated public key keeps its session`() =
        runTest {
            // given
            val fixture = fixture()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val firstNode = sampleNode(1)
            val secondNode = sampleNode(2).copy(publicKey = firstNode.publicKey)
            val firstSession = TestWebSocketSession()
            val firstJob = scope.launch { fixture.manage(firstNode, socketAddress(1), firstSession) }
            awaitCondition { fixture.manager.isConnected(firstNode.publicUri) }

            // when
            val secondSession = TestWebSocketSession()
            val secondJob = scope.launch { fixture.manage(secondNode, socketAddress(2), secondSession) }
            awaitCondition { secondSession.closeReason != null }

            // then
            assertTrue(fixture.manager.isConnected(firstNode.publicUri))
            assertFalse(fixture.manager.isConnected(secondNode.publicUri))
            assertEquals(NodeConnectionManager.CAPACITY_CLOSE_REASON, secondSession.closeReason)
            assertEquals(1.0, fixture.rejectionCount(PeerSessionAdmissionRejection.PUBLIC_KEY_CAPACITY))

            fixture.manager.clear()
            listOf(firstJob, secondJob).joinAll()
            scope.cancel()
        }

    @Test
    fun `reserves global capacity for exact outbound default nodes`() {
        // given
        val trustedUri = URI("ws://trusted.example:8080")
        val properties =
            NetworkProperties().apply {
                maxActivePeerSessions = 4
                maxPeerSessionsPerAddress = 4
                maxPeerSessionsPerPrefix = 4
                maxPeerSessionsPerPublicKey = 1
                defaultNodes = mutableSetOf(trustedUri.toString())
            }
        val fixture = fixture(properties = properties)
        val untrustedReservations =
            (1..3).map { index ->
                fixture.manager
                    .reserveInbound(sampleNode(index).publicUri, InetAddress.getByName("192.0.$index.1"))
                    .acceptedReservation()
            }

        // when
        val untrusted = fixture.manager.reserveInbound(trustedUri, InetAddress.getByName("192.0.4.1"))
        val trusted = fixture.manager.reserveOutbound(trustedUri)

        // then
        untrusted.assertRejected(PeerSessionAdmissionRejection.TRUSTED_CAPACITY_RESERVED)
        assertInstanceOf(PeerSessionReservationResult.Accepted::class.java, trusted)
        assertEquals(3, untrustedReservations.size)
        assertEquals(4, fixture.manager.pendingConnectionCount)
        assertEquals(1.0, fixture.rejectionCount(PeerSessionAdmissionRejection.TRUSTED_CAPACITY_RESERVED))
        fixture.manager.clear()
    }

    @Test
    fun `inbound and outbound sessions share address limits`() =
        runTest {
            // given
            val properties =
                NetworkProperties().apply {
                    maxActivePeerSessions = 4
                    maxPeerSessionsPerAddress = 1
                    maxPeerSessionsPerPrefix = 4
                    maxPeerSessionsPerPublicKey = 1
                }
            val fixture = fixture(properties = properties)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val address = InetSocketAddress(InetAddress.getByName("203.0.113.20"), 8_000)
            val inboundNode = sampleNode(1)
            val inboundSession = TestWebSocketSession()
            val inboundJob = scope.launch { fixture.manage(inboundNode, address, inboundSession) }
            awaitCondition { fixture.manager.isConnected(inboundNode.publicUri) }
            val outboundNode = sampleNode(2)
            val reservation = fixture.manager.reserveOutbound(outboundNode.publicUri).acceptedReservation()
            val outboundSession = TestWebSocketSession()

            // when
            fixture.manager.manage(reservation, outboundNode, address, outboundSession)

            // then
            assertEquals(NodeConnectionManager.CAPACITY_CLOSE_REASON, outboundSession.closeReason)
            assertFalse(fixture.manager.isConnected(outboundNode.publicUri))
            assertEquals(1.0, fixture.rejectionCount(PeerSessionAdmissionRejection.ADDRESS_CAPACITY))

            fixture.manager.release(reservation)
            fixture.manager.clear()
            inboundJob.join()
            scope.cancel()
        }

    @Test
    fun `duplicate pending URI is rejected until reservation is released`() {
        // given
        val fixture = fixture()
        val node = sampleNode(1)
        val first = fixture.manager.reserveOutbound(node.publicUri).acceptedReservation()

        // when
        val duplicate = fixture.manager.reserveInbound(node.publicUri, InetAddress.getByName("192.0.2.1"))

        // then
        duplicate.assertRejected(PeerSessionAdmissionRejection.DUPLICATE_PUBLIC_URI)

        // when
        fixture.manager.release(first)
        val replacement = fixture.manager.reserveOutbound(node.publicUri)

        // then
        assertInstanceOf(PeerSessionReservationResult.Accepted::class.java, replacement)
        fixture.manager.clear()
    }

    private fun fixture(
        inactivityTimeout: Duration? = null,
        ticker: Ticker? = null,
        properties: NetworkProperties = NetworkProperties(),
    ): Fixture {
        val events = CopyOnWriteArrayList<Event>()
        val eventPublisher = mockk<EventPublisher>(relaxed = true)
        every { eventPublisher.publish(any()) } answers {
            events.add(firstArg())
            Unit
        }
        val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
        val thisNode = sampleNode(-1)
        val meterRegistry = SimpleMeterRegistry()
        val manager = NodeConnectionManager(thisNode, messagePublisher, eventPublisher, properties, meterRegistry)
        NetworkMetricProvider(meterRegistry, manager).start()
        if (inactivityTimeout != null && ticker != null) {
            manager.configureExpirationForTesting(inactivityTimeout, ticker)
        }
        return Fixture(manager, messagePublisher, eventPublisher, events, meterRegistry)
    }

    private suspend fun Fixture.fill(count: Int): ConnectionPool {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nodes = List(count) { sampleNode(it) }
        val sessions = List(count) { TestWebSocketSession() }
        val jobs =
            nodes.mapIndexed { index, node ->
                scope.launch {
                    runCatching { manage(node, socketAddress(index), sessions[index]) }
                }
            }
        awaitCondition { manager.connectionCount == count }
        awaitCondition {
            runCatching {
                verify(exactly = count) {
                    eventPublisher.publish(match { it is NodeConnected })
                }
            }.isSuccess
        }
        return ConnectionPool(scope, nodes, sessions, jobs)
    }

    private fun sampleNode(seed: Int): AttoNode =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(ByteBuffer.allocate(32).putInt(seed).array()),
            publicUri = URI("ws://127.0.0.1:${20_000 + seed + 1}"),
            features = setOf(NodeFeature.VOTING),
        )

    private fun socketAddress(seed: Int): InetSocketAddress =
        InetSocketAddress(
            InetAddress.getByAddress(
                byteArrayOf(
                    10,
                    (seed / 16_384).toByte(),
                    ((seed / 64) % 256).toByte(),
                    ((seed % 64) + 1).toByte(),
                ),
            ),
            8_000,
        )

    private fun ipv6Address(
        prefix: ByteArray,
        suffix: Int,
    ): InetAddress =
        Inet6Address.getByAddress(
            null,
            ByteArray(16).also {
                prefix.copyInto(it)
                ByteBuffer.wrap(it, 12, 4).putInt(suffix)
            },
            -1,
        )

    private fun PeerSessionReservationResult.acceptedReservation(): PeerSessionReservation =
        assertInstanceOf(PeerSessionReservationResult.Accepted::class.java, this).reservation

    private fun PeerSessionReservationResult.assertRejected(expected: PeerSessionAdmissionRejection) {
        assertEquals(expected, assertInstanceOf(PeerSessionReservationResult.Rejected::class.java, this).reason)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        await().atMost(10, TimeUnit.SECONDS).until(condition)
    }

    private data class Fixture(
        val manager: NodeConnectionManager,
        val messagePublisher: NetworkMessagePublisher,
        val eventPublisher: EventPublisher,
        val events: CopyOnWriteArrayList<Event>,
        val meterRegistry: SimpleMeterRegistry,
    ) {
        fun rejectionCount(rejection: PeerSessionAdmissionRejection): Double =
            meterRegistry
                .get("network.peers.admission.rejections")
                .tag("reason", rejection.metricTag)
                .counter()
                .count()

        suspend fun manage(
            node: AttoNode,
            address: InetSocketAddress,
            session: TestWebSocketSession,
        ) {
            when (val result = manager.reserveInbound(node.publicUri, address.address)) {
                is PeerSessionReservationResult.Accepted -> {
                    try {
                        manager.manage(result.reservation, node, address, session)
                    } finally {
                        manager.release(result.reservation)
                    }
                }

                is PeerSessionReservationResult.Rejected -> {
                    session.close(result.reason.closeReason)
                }
            }
        }
    }

    private data class ConnectionPool(
        val scope: CoroutineScope,
        val nodes: List<AttoNode>,
        val sessions: List<TestWebSocketSession>,
        val jobs: List<Job>,
    ) {
        fun launchManage(
            fixture: Fixture,
            node: AttoNode,
            session: TestWebSocketSession,
        ): Job =
            scope.launch {
                runCatching {
                    fixture.manage(node, InetSocketAddress(InetAddress.getLoopbackAddress(), 8_000), session)
                }
            }

        fun close() {
            scope.cancel()
        }
    }
}

private class MutableTicker : Ticker {
    private val nanos = AtomicLong()

    override fun read(): Long = nanos.get()

    fun advance(duration: Duration) {
        nanos.addAndGet(duration.toNanos())
    }
}

private class TestWebSocketSession(
    private val closeIncomingOnClose: Boolean = true,
    private val closeGate: CompletableDeferred<Unit>? = null,
) : WebSocketSession {
    private val job = SupervisorJob()
    private val incomingChannel = Channel<Frame>(Channel.UNLIMITED)
    private val outgoingChannel = Channel<Frame>(Channel.UNLIMITED)

    @Volatile
    var closeReason: CloseReason? = null
        private set

    val closeCount: Int
        get() = closeCounter.get()

    val closeStarted = CompletableDeferred<Unit>()

    private val closeCounter = AtomicInteger()

    override val coroutineContext: CoroutineContext = job + Dispatchers.Unconfined
    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override val incoming: ReceiveChannel<Frame> = incomingChannel
    override val outgoing: SendChannel<Frame> = outgoingChannel
    override val extensions: List<WebSocketExtension<*>> = emptyList()

    override suspend fun send(frame: Frame) {
        outgoingChannel.send(frame)
        if (frame is Frame.Close) {
            closeCounter.incrementAndGet()
            closeReason = frame.readReason()
            closeStarted.complete(Unit)
            closeGate?.await()
            if (closeIncomingOnClose) {
                incomingChannel.close()
            }
        }
    }

    override suspend fun flush() = Unit

    override fun terminate() {
        incomingChannel.cancel()
        outgoingChannel.cancel()
        job.cancel()
    }

    suspend fun sendIncoming(frame: Frame) {
        incomingChannel.send(frame)
    }

    fun completeIncoming() {
        incomingChannel.close()
    }

    fun failIncoming(exception: Throwable) {
        incomingChannel.close(exception)
    }
}
