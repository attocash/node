package cash.atto.node.network

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.netty.Connection
import reactor.netty.NettyPipeline
import reactor.netty.http.server.HttpServer
import reactor.netty.http.server.WebsocketServerSpec
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class PeerNettyServerTest {
    @Test
    fun `installs aggregation before delayed session run receives fragmented frames`(): Unit =
        runBlocking {
            for (size in listOf(300, 301)) {
                // Given
                val expected = ByteArray(size) { it.toByte() }
                val startApplication = CompletableDeferred<Unit>()
                val frameQueued = CompletableDeferred<Pair<Int, Boolean>>()
                val received = CompletableDeferred<ByteArray>()
                val listener = ClosingListener()
                val server =
                    HttpServer
                        .create()
                        .host("127.0.0.1")
                        .port(0)
                        .route { routes ->
                            routes.ws(
                                "/",
                                { inbound, outbound ->
                                    var connection: Connection? = null
                                    inbound.withConnection { connection = it }
                                    val transport = requireNotNull(connection)
                                    val session = ReactorPeerSession(transport, inbound, outbound)
                                    transport.channel().pipeline().addBefore(
                                        NettyPipeline.ReactiveBridge,
                                        "queued-binary-observer",
                                        object : ChannelInboundHandlerAdapter() {
                                            override fun channelRead(
                                                context: ChannelHandlerContext,
                                                message: Any,
                                            ) {
                                                val binary =
                                                    if (message is BinaryWebSocketFrame) {
                                                        message.content().readableBytes() to message.isFinalFragment
                                                    } else {
                                                        null
                                                    }
                                                context.fireChannelRead(message)
                                                binary?.let { frameQueued.complete(it) }
                                            }
                                        },
                                    )
                                    mono {
                                        startApplication.await()
                                        session.run { received.complete(it.incoming.first()) }
                                    }.then()
                                },
                                WebsocketServerSpec
                                    .builder()
                                    .maxFramePayloadLength(PeerSession.MAX_MESSAGE_SIZE)
                                    .handlePing(false)
                                    .build(),
                            )
                        }.bindNow(Duration.ofSeconds(5))
                try {
                    HttpClient.newHttpClient().use { client ->
                        val socket = client.connect(server.port(), listener)
                        try {
                            // When: fragments reach the transport before the application subscribes.
                            socket.sendBinary(ByteBuffer.wrap(expected.copyOfRange(0, 150)), false).get(5, TimeUnit.SECONDS)
                            socket.sendBinary(ByteBuffer.wrap(expected.copyOfRange(150, size)), true).get(5, TimeUnit.SECONDS)
                            if (size == 300) {
                                // Observe the same frame dispatch; a later Ping can stall without an inbound subscriber.
                                assertEquals(300 to true, withTimeout(5.seconds) { frameQueued.await() })
                            } else {
                                withTimeout(5.seconds) { listener.closed.await() }
                            }

                            // Then
                            assertFalse(received.isCompleted)
                            startApplication.complete(Unit)
                            if (size == 300) {
                                assertArrayEquals(expected, withTimeout(5.seconds) { received.await() })
                            } else {
                                assertFalse(received.isCompleted, "Oversized queued fragments must not reach the application")
                            }
                        } finally {
                            socket.abort()
                        }
                    }
                } finally {
                    startApplication.complete(Unit)
                    server.disposeNow(Duration.ofSeconds(5))
                }
            }
        }

    @Test
    fun `accepts fragmented 300 byte binary with interleaved controls and ignores text`(): Unit =
        runBlocking {
            // Given
            val expected = ByteArray(300) { it.toByte() }
            val received = CompletableDeferred<ByteArray>()
            val port = ServerSocket(0).use { it.localPort }
            PeerNettyServer(
                port,
                handshake = { _, _ -> },
                webSocket = { _, session -> received.complete(session.incoming.first()) },
            ).use {
                HttpClient.newHttpClient().use { client ->
                    val socket = client.connect(port, ClosingListener())
                    try {
                        // When
                        socket.sendText("ignored text", true).get(5, TimeUnit.SECONDS)
                        socket.sendBinary(ByteBuffer.wrap(expected.copyOfRange(0, 150)), false).get(5, TimeUnit.SECONDS)
                        socket.sendPing(ByteBuffer.wrap(byteArrayOf(1))).get(5, TimeUnit.SECONDS)
                        socket.sendPong(ByteBuffer.wrap(byteArrayOf(2))).get(5, TimeUnit.SECONDS)
                        socket.sendBinary(ByteBuffer.wrap(expected.copyOfRange(150, 300)), true).get(5, TimeUnit.SECONDS)

                        // Then
                        assertArrayEquals(expected, withTimeout(5.seconds) { received.await() })
                    } finally {
                        socket.abort()
                    }
                }
            }
        }

    @Test
    fun `closes single and fragmented binary above 300 bytes without delivering it`(): Unit =
        runBlocking {
            for (fragmented in listOf(false, true)) {
                // Given
                val messages = mutableListOf<ByteArray>()
                val applicationStopped = CompletableDeferred<Unit>()
                val listener = ClosingListener()
                val port = ServerSocket(0).use { it.localPort }
                PeerNettyServer(
                    port,
                    handshake = { _, _ -> },
                    webSocket = { _, session ->
                        try {
                            session.incoming.toList(messages)
                        } finally {
                            applicationStopped.complete(Unit)
                        }
                    },
                ).use {
                    HttpClient.newHttpClient().use { client ->
                        val socket = client.connect(port, listener)
                        try {
                            // When
                            if (fragmented) {
                                socket.sendBinary(ByteBuffer.wrap(ByteArray(150)), false).get(5, TimeUnit.SECONDS)
                                socket.sendBinary(ByteBuffer.wrap(ByteArray(151)), true).get(5, TimeUnit.SECONDS)
                            } else {
                                socket.sendBinary(ByteBuffer.wrap(ByteArray(301)), true).get(5, TimeUnit.SECONDS)
                            }
                            withTimeout(5.seconds) {
                                listener.closed.await()
                                applicationStopped.await()
                            }

                            // Then
                            assertTrue(messages.isEmpty(), "Oversized fragmented=$fragmented message must not be delivered")
                        } finally {
                            socket.abort()
                        }
                    }
                }
            }
        }

    private fun HttpClient.connect(
        port: Int,
        listener: WebSocket.Listener,
    ): WebSocket =
        newWebSocketBuilder()
            .buildAsync(URI("ws://127.0.0.1:$port/"), listener)
            .get(5, TimeUnit.SECONDS)

    private class ClosingListener : WebSocket.Listener {
        val closed = CompletableDeferred<Unit>()

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletionStage<*> {
            closed.complete(Unit)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable,
        ) {
            closed.complete(Unit)
        }
    }
}
