package cash.atto.node.network

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoChallenge
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoSigner
import cash.atto.commons.fromHexToByteArray
import cash.atto.commons.toHex
import cash.atto.commons.toSignerBlocking
import cash.atto.node.network.guardian.Guardian
import cash.atto.node.network.guardian.InboundConnectionDecision
import cash.atto.node.transaction.Transaction
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.LoopResources
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class NetworkProcessorTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `accepts 2400 byte handshake bodies and closes fixed and chunked oversized request sockets`() {
        // Given
        RunningServer().use { server ->
            val encoded = json.encodeToString(server.request)
            val atLimit = encoded + " ".repeat(MAX_COUNTER_CHALLENGE_RESPONSE_SIZE_BYTES - encoded.length)
            for (chunked in listOf(false, true)) {
                // When
                val acceptedStatus =
                    server.socket().use { socket ->
                        socket.writeRequest(atLimit, chunked)
                        socket.getInputStream().readResponseStatus()
                    }

                // Then: a complete body at the limit reaches challenge authentication.
                assertEquals(400, acceptedStatus, "At-limit chunked=$chunked body must reach authentication")

                // When
                server.socket().use { socket ->
                    socket.writeRequest(" ".repeat(MAX_COUNTER_CHALLENGE_RESPONSE_SIZE_BYTES + 1), chunked)
                    val oversizedStatus = socket.getInputStream().readResponseStatus()

                    // Then: a keep-alive connection cannot be reused after body cancellation.
                    assertEquals(413, oversizedStatus, "Oversized chunked=$chunked body must be rejected")
                    socket.assertClosed()
                }
            }
        }
    }

    @Test
    fun `times out incomplete inbound body and closes its socket`() {
        // Given
        RunningServer().use { server ->
            server.socket().use { socket ->
                socket.writeAscii(
                    "POST /handshakes HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
                        "Content-Length: 10\r\nConnection: keep-alive\r\n\r\n{",
                )

                // When
                val status = socket.getInputStream().readResponseStatus()

                // Then
                assertEquals(408, status)
                socket.assertClosed()
            }
        }
    }

    @Test
    fun `rejects banned and rate limited inbound requests before waiting for their body`() {
        // Given
        for ((decision, expectedStatus) in listOf(InboundConnectionDecision.Banned to 403, InboundConnectionDecision.RateLimited to 429)) {
            RunningServer(decision).use { server ->
                server.socket().use { socket ->
                    socket.soTimeout = 2_000
                    socket.writeAscii(
                        "POST /handshakes HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
                            "Content-Length: 2400\r\nConnection: keep-alive\r\n\r\n",
                    )

                    // When
                    val status = socket.getInputStream().readResponseStatus()

                    // Then
                    assertEquals(expectedStatus, status)
                    socket.assertClosed()
                }
            }
        }
    }

    @Test
    fun `preserves malformed JSON failure status and closes cancelled request socket`() {
        // Given
        RunningServer().use { server ->
            server.socket().use { socket ->
                socket.writeRequest("{", chunked = false)

                // When
                val status = socket.getInputStream().readResponseStatus()

                // Then
                assertEquals(500, status)
                socket.assertClosed()
            }
        }
    }

    @Test
    fun `times out inbound HTTP handshake signing and cancels the signer`() =
        runBlocking {
            // Given
            val port = freePort()
            val localNode = node(AttoPrivateKey.generate().toSignerBlocking().publicKey, URI("ws://127.0.0.1:$port"))
            val peerSigner = AttoPrivateKey.generate().toSignerBlocking()
            val peerNode = node(peerSigner.publicKey, URI("ws://127.0.0.1:${freePort()}"))
            val genesisHash = AttoHash(ByteArray(32) { 1 })
            val genesisTransaction =
                mockk<Transaction> {
                    every { hash } returns genesisHash
                }
            val signer = mockk<AttoSigner>()
            val signingStarted = CompletableDeferred<Unit>()
            val signingStopped = CompletableDeferred<Unit>()
            val signingAttempts = AtomicInteger()
            val signerInvocations = AtomicInteger()
            coEvery { signer.sign(any<AttoChallenge>(), any<AttoInstant>()) } coAnswers {
                awaitSigningUntilCancelled(signingStarted, signingStopped, signingAttempts, signerInvocations)
            }
            val dnsResolver = mockk<NetworkDnsResolver>()
            coEvery { dnsResolver.getByName(any()) } returns InetAddress.getByName("127.0.0.1")
            val peerClient = PeerNettyClient(dnsResolver)

            val processor =
                NetworkProcessor(
                    genesisTransaction = genesisTransaction,
                    thisNode = localNode,
                    signer = signer,
                    environment = MockEnvironment().withProperty("websocket.port", port.toString()),
                    networkProperties = NetworkProperties(),
                    peerUriValidator = mockk<PeerUriValidator>(),
                    handshakeCallbackService = mockk<HandshakeCallbackService>(),
                    dnsResolver = dnsResolver,
                    peerClient = peerClient,
                    connectionManager = mockk(relaxed = true),
                    guardian =
                        mockk<Guardian> {
                            every { requestInboundConnection(any()) } returns InboundConnectionDecision.Accepted
                        },
                )
            val clientLoops = LoopResources.create("handshake-test-http", 1, true)
            val client =
                WebClient
                    .builder()
                    .clientConnector(ReactorClientHttpConnector(HttpClient.newConnection().runOn(clientLoops).disableRetry(true)))
                    .build()
            var challenge: String? = null

            try {
                val generatedChallenge = ChallengeStore.generate(peerNode.publicUri)
                challenge = generatedChallenge
                val timestamp = AttoInstant.now()
                val counterChallenge =
                    (localNode.publicUri.toString().toByteArray() + ByteArray(ChallengeStore.CHALLENGE_SIZE) { 1 }).toHex()
                val request =
                    CounterChallengeResponse(
                        challenge = generatedChallenge,
                        genesis = genesisHash,
                        node = peerNode,
                        timestamp = timestamp,
                        signature =
                            peerSigner.sign(
                                AttoHash.hash(
                                    64,
                                    peerNode.publicKey.value,
                                    generatedChallenge.fromHexToByteArray(),
                                    timestamp.toByteArray(),
                                ),
                            ),
                        counterChallenge = counterChallenge,
                    )

                // When
                val responseJob =
                    async {
                        client.postHandshake(port, request)
                    }
                withTimeout(10.seconds) { signingStarted.await() }
                val response = withTimeout(10.seconds) { responseJob.await() }
                withTimeout(1.seconds) { signingStopped.await() }

                // Then
                assertEquals(408, response.value())
                assertEquals(1, signerInvocations.get())
                assertTrue(signingAttempts.get() > 1)

                // When: a malformed challenge is rejected before invoking the signer again.
                val rejectedResponse =
                    withTimeout(2.seconds) {
                        client.postHandshake(port, request.copy(challenge = "00"))
                    }

                // Then
                assertEquals(400, rejectedResponse.value())
                coVerify(exactly = 1) { signer.sign(any<AttoChallenge>(), any<AttoInstant>()) }
            } finally {
                try {
                    cleanup(clientLoops, processor, challenge)
                } finally {
                    peerClient.close()
                }
            }
        }

    private fun node(
        publicKey: AttoPublicKey,
        publicUri: URI,
    ): AttoNode =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = AttoNode.CURRENT_PROTOCOL_VERSION,
            algorithm = AttoAlgorithm.V1,
            publicKey = publicKey,
            publicUri = publicUri,
            features = setOf(NodeFeature.VOTING),
        )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun awaitSigningUntilCancelled(
        signingStarted: CompletableDeferred<Unit>,
        signingStopped: CompletableDeferred<Unit>,
        signingAttempts: AtomicInteger,
        signerInvocations: AtomicInteger,
    ): AttoSignature {
        signerInvocations.incrementAndGet()
        signingStarted.complete(Unit)
        return try {
            repeatSignerWorkUntilCancelled(signingAttempts)
        } finally {
            signingStopped.complete(Unit)
        }
    }

    private suspend fun repeatSignerWorkUntilCancelled(signingAttempts: AtomicInteger): AttoSignature {
        while (currentCoroutineContext().isActive) {
            signingAttempts.incrementAndGet()
            delay(10)
        }
        awaitCancellation()
    }

    private suspend fun WebClient.postHandshake(
        port: Int,
        request: CounterChallengeResponse,
    ): HttpStatusCode =
        post()
            .uri("http://127.0.0.1:$port/handshakes")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json.encodeToString(request))
            .exchangeToMono { response -> response.releaseBody().thenReturn(response.statusCode()) }
            .awaitSingle()

    private fun cleanup(
        clientLoops: LoopResources,
        processor: NetworkProcessor,
        challenge: String?,
    ) {
        try {
            clientLoops.disposeLater().block(Duration.ofSeconds(5))
        } finally {
            stopProcessorAndRemoveChallenge(processor, challenge)
        }
    }

    private fun stopProcessorAndRemoveChallenge(
        processor: NetworkProcessor,
        challenge: String?,
    ) {
        try {
            processor.stop()
        } finally {
            challenge?.let(ChallengeStore::remove)
        }
    }

    private inner class RunningServer(
        decision: InboundConnectionDecision = InboundConnectionDecision.Accepted,
    ) : AutoCloseable {
        private val port = freePort()
        private val resolver = NetworkDnsResolver()
        private val peerClient = PeerNettyClient(resolver)
        private val signer = AttoPrivateKey.generate().toSignerBlocking()
        private val localNode = node(signer.publicKey, URI("ws://127.0.0.1:$port"))
        private val genesis = AttoHash(ByteArray(32) { 3 })
        private val processor =
            NetworkProcessor(
                genesisTransaction = mockk<Transaction> { every { hash } returns genesis },
                thisNode = localNode,
                signer = signer,
                environment = MockEnvironment().withProperty("websocket.port", port.toString()),
                networkProperties = NetworkProperties(),
                peerUriValidator = mockk(),
                handshakeCallbackService = mockk(),
                dnsResolver = resolver,
                peerClient = peerClient,
                connectionManager = mockk(relaxed = true),
                guardian =
                    mockk<Guardian> {
                        every { requestInboundConnection(any()) } returns decision
                    },
            )
        val request = CounterChallengeResponse("00", genesis, localNode, AttoInstant.now(), AttoSignature(ByteArray(64)), "00")

        fun socket(): Socket = Socket("127.0.0.1", port).apply { soTimeout = 10_000 }

        override fun close() {
            try {
                processor.stop()
            } finally {
                peerClient.close()
                resolver.close()
            }
        }
    }

    private fun Socket.writeRequest(
        body: String,
        chunked: Boolean,
    ) {
        val framing =
            if (chunked) {
                "Transfer-Encoding: chunked\r\n\r\n${body.length.toString(16)}\r\n$body\r\n0\r\n\r\n"
            } else {
                "Content-Length: ${body.length}\r\n\r\n$body"
            }
        writeAscii(
            "POST /handshakes HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nConnection: keep-alive\r\n$framing",
        )
    }

    private fun Socket.writeAscii(text: String) {
        getOutputStream().write(text.toByteArray(Charsets.US_ASCII))
        getOutputStream().flush()
    }

    private fun Socket.assertClosed() {
        try {
            assertEquals(-1, getInputStream().read(), "Cancelled request socket must close before another request can reuse it")
        } catch (_: SocketException) {
            // Reset and EOF both prevent a client from reusing this connection.
        }
    }

    private fun InputStream.readResponseStatus(): Int {
        val status = readAsciiLine().split(' ')[1].toInt()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readAsciiLine()
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        if (headers["transfer-encoding"] == "chunked") {
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
        return status
    }

    private fun InputStream.readAsciiLine(): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = read()
            check(value >= 0) { "Unexpected EOF reading peer HTTP response" }
            if (value == '\n'.code) break
            bytes.add(value.toByte())
            check(bytes.size < 16_384)
        }
        return bytes.toByteArray().decodeToString().removeSuffix("\r")
    }
}
