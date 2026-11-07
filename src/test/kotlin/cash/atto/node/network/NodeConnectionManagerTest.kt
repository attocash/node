package cash.atto.node.network

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.node.EventPublisher
import cash.atto.protocol.AttoKeepAlive
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class NodeConnectionManagerTest {
    @Test
    fun `connection metadata immediately follows registration replacement and completion`() =
        runTest {
            // Given
            val manager = manager()
            val original = node(1)
            val replacement = original.copy(publicKey = publicKey(2))
            val firstSocket = InetSocketAddress("127.0.0.1", 10_001)
            val nextSocket = InetSocketAddress("127.0.0.1", 10_002)
            val firstSession = ChannelSession()
            val nextSession = ChannelSession()
            try {
                // When
                val first = launch(start = CoroutineStart.UNDISPATCHED) { manager.manage(original, firstSocket, firstSession) }
                val initialSnapshot = manager.snapshot()

                // Then
                assertEquals(listOf(ConnectedPeer(original, firstSocket)), initialSnapshot)
                assertTrue(manager.isConnected(original.publicUri))

                // When
                val next = launch(start = CoroutineStart.UNDISPATCHED) { manager.manage(replacement, nextSocket, nextSession) }
                firstSession.incoming.close()
                first.join()

                // Then
                assertEquals(listOf(ConnectedPeer(replacement, nextSocket)), manager.snapshot())
                assertEquals(listOf(ConnectedPeer(original, firstSocket)), initialSnapshot)

                // When
                nextSession.incoming.close()
                next.join()

                // Then
                assertTrue(manager.snapshot().isEmpty())
                assertFalse(manager.isConnected(original.publicUri))
            } finally {
                firstSession.cancel()
                nextSession.cancel()
                manager.stop()
            }
        }

    @Test
    fun `buffered keepalive from replaced session cannot restore old connection`() =
        runTest {
            // Given
            val manager = manager()
            val original = node(1)
            val replacement = original.copy(publicKey = publicKey(2))
            val firstSocket = InetSocketAddress("127.0.0.1", 10_001)
            val nextSocket = InetSocketAddress("127.0.0.1", 10_002)
            val firstSession = ChannelSession()
            val nextSession = ChannelSession()
            try {
                val first = launch(start = CoroutineStart.UNDISPATCHED) { manager.manage(original, firstSocket, firstSession) }
                firstSession.incoming.send(Frame.Binary(true, NetworkSerializer.serialize(AttoKeepAlive())))

                // When: replace before the scheduled old frame collector resumes.
                val next = launch(start = CoroutineStart.UNDISPATCHED) { manager.manage(replacement, nextSocket, nextSession) }
                runCurrent()

                // Then
                assertEquals(listOf(ConnectedPeer(replacement, nextSocket)), manager.snapshot())

                // When
                firstSession.incoming.close()
                nextSession.incoming.close()
                first.join()
                next.join()
            } finally {
                firstSession.cancel()
                nextSession.cancel()
                manager.stop()
            }
        }

    @Test
    fun `cancelling managed work removes peer and initiates transport close`() =
        runTest {
            // Given
            val manager = manager()
            val peer = node(1)
            val session = ChannelSession()
            try {
                val managed =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        manager.manage(peer, InetSocketAddress("127.0.0.1", 10_001), session)
                    }

                // When
                managed.cancelAndJoin()

                // Then
                assertFalse(manager.isConnected(peer.publicUri))
                assertTrue(manager.snapshot().isEmpty())
                assertTrue(session.outgoing.tryReceive().getOrNull() is Frame.Close)
            } finally {
                session.cancel()
                manager.stop()
            }
        }

    private fun manager(): NodeConnectionManager =
        NodeConnectionManager(
            thisNode = node(100),
            messagePublisher = mockk(relaxed = true),
            eventPublisher = mockk<EventPublisher>(relaxed = true),
        )

    // Sending Close starts graceful shutdown; frames already received can still be consumed until the peer closes.
    private class ChannelSession : WebSocketSession {
        override val coroutineContext: CoroutineContext = SupervisorJob()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        override val outgoing = Channel<Frame>(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()

        override suspend fun flush() = Unit

        @Deprecated("Use cancel() instead.", level = DeprecationLevel.ERROR)
        override fun terminate() {
            cancel()
        }
    }

    private fun node(marker: Byte): AttoNode =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = AttoNode.CURRENT_PROTOCOL_VERSION,
            algorithm = AttoAlgorithm.V1,
            publicKey = publicKey(marker),
            publicUri = URI("ws://127.0.0.1:${8_000 + marker}"),
            features = setOf(NodeFeature.HISTORICAL),
        )

    private fun publicKey(marker: Byte): AttoPublicKey = AttoPublicKey(ByteArray(32) { marker })
}
