package cash.atto.node.network

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.toSignerBlocking
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionStreamRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class PeerNettyClientTest {
    @Test
    fun `drains queued binary after remote normal close before collector starts`() =
        runBlocking {
            // Given
            val expected = byteArrayOf(4, 2)
            val applicationStarted = CountDownLatch(1)
            val remoteClosed = CompletableDeferred<Unit>()
            val releaseCollector = CompletableDeferred<Unit>()
            RawPeerServer { socket, headers ->
                socket.upgradeWebSocket(headers)
                check(applicationStarted.await(5, TimeUnit.SECONDS))
                socket.writeFrame(2, expected)
                socket.writeFrame(8, byteArrayOf(3, -24))
                try {
                    while (true) {
                        val frame = socket.readFrame() ?: break
                        if (frame.opcode == 8) break
                    }
                } catch (_: SocketException) {
                    // The completed close handshake may already have disposed the client socket.
                }
                remoteClosed.complete(Unit)
            }.use { server ->
                withClient { client ->
                    val messages = mutableListOf<ByteArray>()
                    val connection =
                        async {
                            client.webSocket(server.websocketUri, emptyMap()) { session, _ ->
                                applicationStarted.countDown()
                                releaseCollector.await()
                                session.incoming.toList(messages)
                            }
                        }
                    withTimeout(5.seconds) { remoteClosed.await() }

                    // When
                    releaseCollector.complete(Unit)
                    withTimeout(5.seconds) { connection.await() }

                    // Then
                    assertEquals(1, messages.size)
                    assertArrayEquals(expected, messages.single())
                }
            }
        }

    @Test
    fun `flushes accepted binary messages in order before normal close`() =
        runBlocking {
            // Given
            val expected = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
            val received = CompletableDeferred<Pair<List<ByteArray>, Int>>()
            RawPeerServer { socket, headers ->
                socket.upgradeWebSocket(headers)
                val messages = mutableListOf<ByteArray>()
                while (true) {
                    val frame = checkNotNull(socket.readFrame())
                    if (frame.opcode == 2) messages.add(frame.payload)
                    if (frame.opcode == 8) {
                        check(frame.payload.size >= 2)
                        val code = ((frame.payload[0].toInt() and 0xff) shl 8) or (frame.payload[1].toInt() and 0xff)
                        received.complete(messages to code)
                        try {
                            socket.writeFrame(8, frame.payload)
                        } catch (_: SocketException) {
                            // Local graceful close need not wait for this remote acknowledgement.
                        }
                        break
                    }
                }
            }.use { server ->
                withClient { client ->
                    // When
                    withTimeout(5.seconds) {
                        client.webSocket(server.websocketUri, emptyMap()) { session, _ ->
                            expected.forEach { session.send(it) }
                            session.close()
                        }
                    }
                    val (messages, code) = withTimeout(5.seconds) { received.await() }

                    // Then
                    assertEquals(1000, code)
                    assertEquals(expected.size, messages.size)
                    expected.zip(messages).forEach { (sent, actual) -> assertArrayEquals(sent, actual) }
                }
            }
        }

    @Test
    fun `closes overflowing inbound text queue while application collector is inactive`() =
        runBlocking {
            // Given
            val applicationStarted = CountDownLatch(1)
            val remoteClosed = CompletableDeferred<Unit>()
            val capacity = AttoTransactionStreamRequest.MAX_TRANSACTIONS.toInt()
            RawPeerServer { socket, headers ->
                socket.upgradeWebSocket(headers)
                check(applicationStarted.await(5, TimeUnit.SECONDS))
                try {
                    repeat(capacity + 1) { socket.writeFrame(1, byteArrayOf(65)) }
                    while (true) {
                        val frame = socket.readFrame() ?: break
                        if (frame.opcode == 8) break
                    }
                } catch (_: SocketException) {
                    // A reset while writing or reading also proves the overflow closed this socket.
                }
                remoteClosed.complete(Unit)
            }.use { server ->
                withClient { client ->
                    // When
                    val result =
                        withTimeout(5.seconds) {
                            runCatching {
                                client.webSocket(server.websocketUri, emptyMap()) { _, _ ->
                                    applicationStarted.countDown()
                                    awaitCancellation()
                                }
                            }
                        }

                    // Then
                    assertTrue(result.isFailure)
                    withTimeout(5.seconds) { remoteClosed.await() }
                }
            }
        }

    @Test
    fun `aggregates fragmented binary through 300 bytes and ignores control and text`() =
        runBlocking {
            // Given
            val expected = ByteArray(300) { it.toByte() }
            RawPeerServer { socket, headers ->
                socket.upgradeWebSocket(headers)
                socket.writeFrame(9, byteArrayOf(1))
                socket.writeFrame(10, byteArrayOf(2))
                socket.writeFrame(1, "ignored text".toByteArray())
                socket.writeFrame(2, expected.copyOfRange(0, 150), final = false)
                socket.writeFrame(0, expected.copyOfRange(150, 300))
                var frame = socket.readFrame()
                while (frame != null && frame.opcode != 8) frame = socket.readFrame()
            }.use { server ->
                withClient { client ->
                    // When
                    withTimeout(5.seconds) {
                        client.webSocket(server.websocketUri, emptyMap()) { session, _ ->
                            // Then
                            assertArrayEquals(expected, session.incoming.first())
                        }
                    }
                }
            }
        }

    @Test
    fun `rejects binary larger than 300 bytes for single and fragmented messages`() =
        runBlocking {
            // Given
            for (fragmented in listOf(false, true)) {
                val closed = CompletableDeferred<Unit>()
                val messages = mutableListOf<ByteArray>()
                RawPeerServer { socket, headers ->
                    socket.upgradeWebSocket(headers)
                    if (fragmented) {
                        socket.writeFrame(2, ByteArray(150), final = false)
                        socket.writeFrame(0, ByteArray(151))
                    } else {
                        socket.writeFrame(2, ByteArray(301))
                    }
                    try {
                        while (true) {
                            val frame = socket.readFrame() ?: break
                            if (frame.opcode == 8) break
                        }
                    } catch (_: SocketException) {
                        // Rejecting an oversized frame may reset the socket instead of completing a close handshake.
                    } finally {
                        closed.complete(Unit)
                    }
                }.use { server ->
                    withClient { client ->
                        // When
                        withTimeout(5.seconds) {
                            runCatching {
                                client.webSocket(server.websocketUri, emptyMap()) { session, _ ->
                                    session.incoming.toList(messages)
                                }
                            }
                            closed.await()
                        }

                        // Then
                        assertTrue(messages.isEmpty(), "Oversized fragmented=$fragmented message must not be delivered")
                    }
                }
            }
        }

    @Test
    fun `preserves handshake callback rejection statuses without following redirect`() =
        runBlocking {
            // Given
            val request = handshakeRequest()
            RawPeerServer { socket, _ -> socket.writeHttp(200, "unused") }.use { redirectTarget ->
                withClient { client ->
                    for (status in listOf(302, 400, 429, 500)) {
                        RawPeerServer { socket, _ ->
                            socket.writeHttp(status, "ignored", "Location: ${redirectTarget.httpUri}\r\n")
                        }.use { rejection ->
                            // When
                            val rejected = withTimeout(5.seconds) { client.postHandshake(rejection.httpUri, request) }

                            // Then
                            assertEquals(status, rejected.status.code())
                            assertEquals(null, rejected.response)
                        }
                    }
                    assertEquals(0, redirectTarget.requestCount.get())
                }
            }
        }

    @Test
    fun `rejects declared and chunked handshake callback responses above 700 bytes`() =
        runBlocking {
            // Given
            val request = handshakeRequest()
            for (chunked in listOf(false, true)) {
                RawPeerServer { socket, _ ->
                    if (chunked) {
                        socket.writeAscii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2bd\r\n${" ".repeat(701)}\r\n0\r\n\r\n")
                    } else {
                        socket.writeHttp(200, " ".repeat(701))
                    }
                }.use { server ->
                    withClient { client ->
                        // When
                        val result = withTimeout(5.seconds) { runCatching { client.postHandshake(server.httpUri, request) } }

                        // Then
                        assertTrue(
                            result.exceptionOrNull().hasCause<HandshakePayloadTooLargeException>(),
                            "Expected callback payload limit for chunked=$chunked, got ${result.exceptionOrNull()}",
                        )
                    }
                }
            }
        }

    @Test
    fun `rejects malformed successful callback and permits a later callback`() =
        runBlocking {
            // Given
            val request = handshakeRequest()
            val response = ChallengeResponse(request.node, request.timestamp, request.signature)
            RawPeerServer { socket, _ ->
                socket.writeHttp(200, "{")
            }.use { malformed ->
                RawPeerServer { socket, _ ->
                    socket.writeHttp(200, Json.encodeToString(ChallengeResponse.serializer(), response))
                }.use { valid ->
                    withClient { client ->
                        // When
                        val result = withTimeout(5.seconds) { runCatching { client.postHandshake(malformed.httpUri, request) } }
                        val nextResult = withTimeout(5.seconds) { client.postHandshake(valid.httpUri, request) }

                        // Then
                        assertTrue(result.isFailure)
                        assertEquals(response, nextResult.response)
                    }
                }
            }
        }

    @Test
    fun `cancelling stalled declared and chunked callback bodies closes the socket`() =
        runBlocking {
            // Given
            val request = handshakeRequest()
            for (chunked in listOf(false, true)) {
                val bodyStarted = CompletableDeferred<Unit>()
                val disconnected = CompletableDeferred<Unit>()
                RawPeerServer { socket, _ ->
                    if (chunked) {
                        socket.writeAscii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\na\r\n{")
                    } else {
                        socket.writeAscii("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n{")
                    }
                    bodyStarted.complete(Unit)
                    try {
                        check(socket.getInputStream().read() == -1)
                    } catch (_: SocketException) {
                        // Either EOF or reset proves that cancellation released the request socket.
                    }
                    disconnected.complete(Unit)
                }.use { server ->
                    withClient { client ->
                        val callback = async { client.postHandshake(server.httpUri, request) }
                        assertTrue(
                            withTimeoutOrNull(5.seconds) {
                                server.await(bodyStarted)
                                true
                            } == true,
                            "Raw peer must start the stalled callback response, chunked=$chunked",
                        )

                        // When
                        callback.cancelAndJoin()

                        // Then
                        assertTrue(
                            withTimeoutOrNull(5.seconds) {
                                server.await(disconnected)
                                true
                            } == true,
                            "Cancelled callback must close the stalled response socket, chunked=$chunked",
                        )
                        assertTrue(callback.isCancelled)
                    }
                }
            }
        }

    @Test
    fun `follows websocket redirect and exposes effective socket after upgrade`() =
        runBlocking {
            // Given
            val expected = byteArrayOf(3, 0, 7)
            var currentAddress: InetSocketAddress? = null
            RawPeerServer { socket, headers ->
                socket.upgradeWebSocket(headers)
                socket.writeFrame(2, expected)
                socket.readFrame()
            }.use { target ->
                RawPeerServer { socket, _ ->
                    socket.writeHttp(307, "", "Location: ${target.websocketUri}\r\n")
                }.use { redirect ->
                    withClient { client ->
                        // When
                        withTimeout(5.seconds) {
                            client.webSocket(redirect.websocketUri, emptyMap()) { session, address ->
                                currentAddress = address
                                assertArrayEquals(expected, session.incoming.first())
                            }
                        }

                        // Then
                        assertEquals(target.port, currentAddress?.port)
                        assertEquals(InetAddress.getByName("127.0.0.1"), currentAddress?.address)
                        assertEquals(1, redirect.requestCount.get())
                        assertEquals(1, target.requestCount.get())
                    }
                }
            }
        }

    private suspend fun withClient(block: suspend (PeerNettyClient) -> Unit) {
        val resolver = NetworkDnsResolver()
        val client = PeerNettyClient(resolver)
        try {
            block(client)
        } finally {
            client.close()
            resolver.close()
        }
    }

    private fun handshakeRequest(): CounterChallengeResponse {
        val node =
            AttoNode(
                network = AttoNetwork.LOCAL,
                protocolVersion = AttoNode.CURRENT_PROTOCOL_VERSION,
                algorithm = AttoAlgorithm.V1,
                publicKey = AttoPrivateKey.generate().toSignerBlocking().publicKey,
                publicUri = URI("ws://127.0.0.1:7070"),
                features = emptySet(),
            )
        return CounterChallengeResponse("00", AttoHash(ByteArray(32)), node, AttoInstant.now(), AttoSignature(ByteArray(64)), "00")
    }

    private inline fun <reified T : Throwable> Throwable?.hasCause(): Boolean = generateSequence(this) { it.cause }.any { it is T }

    private class RawPeerServer(
        handler: (Socket, Map<String, String>) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val closing = AtomicBoolean()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val firstFailure = CompletableDeferred<Throwable>()

        val port: Int = server.localPort
        val httpUri: URI = URI("http://127.0.0.1:$port/handshakes")
        val websocketUri: URI = URI("ws://127.0.0.1:$port")
        val requestCount = AtomicInteger()

        init {
            executor.submit {
                while (!server.isClosed) {
                    val socket =
                        try {
                            server.accept()
                        } catch (_: SocketException) {
                            break
                        }
                    sockets.add(socket)
                    executor.submit {
                        socket.use {
                            try {
                                socket.soTimeout = 10_000
                                val headers = socket.getInputStream().readHttpRequest()
                                requestCount.incrementAndGet()
                                handler(socket, headers)
                            } catch (failure: Throwable) {
                                if (!closing.get()) {
                                    failures.add(failure)
                                    firstFailure.complete(failure)
                                }
                            } finally {
                                sockets.remove(socket)
                            }
                        }
                    }
                }
            }
        }

        suspend fun <T> await(signal: Deferred<T>): T =
            select {
                signal.onAwait { it }
                firstFailure.onAwait { throw AssertionError("Raw peer fixture failed", it) }
            }

        override fun close() {
            closing.set(true)
            server.close()
            sockets.forEach { it.close() }
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Raw peer fixture workers did not stop" }
            val failure = failures.poll()
            if (failure != null) {
                val error = AssertionError("Raw peer fixture failed", failure)
                failures.forEach(error::addSuppressed)
                throw error
            }
        }
    }

    private data class WireFrame(
        val opcode: Int,
        val payload: ByteArray,
    )

    companion object {
        private fun InputStream.readHttpRequest(): Map<String, String> {
            readAsciiLine()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine()
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            if (headers["transfer-encoding"]?.contains("chunked") == true) {
                while (true) {
                    val size = readAsciiLine().substringBefore(';').toInt(16)
                    if (size == 0) {
                        check(readAsciiLine().isEmpty())
                        break
                    }
                    check(readNBytes(size).size == size)
                    check(readAsciiLine().isEmpty())
                }
            } else {
                val size = headers["content-length"]?.toInt() ?: 0
                check(readNBytes(size).size == size)
            }
            return headers
        }

        private fun InputStream.readAsciiLine(): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val value = read()
                check(value != -1) { "Unexpected EOF reading HTTP headers" }
                if (value == '\n'.code) break
                bytes.add(value.toByte())
                check(bytes.size < 16_384)
            }
            return bytes.toByteArray().decodeToString().removeSuffix("\r")
        }

        private fun Socket.writeAscii(text: String) {
            getOutputStream().write(text.toByteArray(Charsets.US_ASCII))
            getOutputStream().flush()
        }

        private fun Socket.writeHttp(
            status: Int,
            body: String,
            extraHeaders: String = "",
        ) {
            writeAscii(
                "HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n$extraHeaders\r\n$body",
            )
        }

        private fun Socket.upgradeWebSocket(headers: Map<String, String>) {
            val key = checkNotNull(headers["sec-websocket-key"]) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(key.toByteArray()))
            writeAscii(
                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n",
            )
        }

        private fun Socket.writeFrame(
            opcode: Int,
            payload: ByteArray,
            final: Boolean = true,
        ) {
            val output = getOutputStream()
            output.write(opcode or if (final) 0x80 else 0)
            if (payload.size < 126) {
                output.write(payload.size)
            } else {
                output.write(126)
                output.write(payload.size ushr 8)
                output.write(payload.size and 0xff)
            }
            output.write(payload)
            output.flush()
        }

        private fun Socket.readFrame(): WireFrame? {
            val input = getInputStream()
            val first = input.read()
            if (first == -1) return null
            val second = input.read()
            check(second >= 0)
            val masked = second and 0x80 != 0
            val length =
                when (val shortLength = second and 0x7f) {
                    126 -> (input.read() shl 8) or input.read()
                    127 -> error("Unexpected oversized client frame")
                    else -> shortLength
                }
            val mask = if (masked) input.readNBytes(4) else byteArrayOf()
            val payload = input.readNBytes(length)
            check(payload.size == length)
            if (masked) {
                payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            }
            return WireFrame(first and 0x0f, payload)
        }
    }
}
