package cash.atto.node.network

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.protocol.AttoNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import reactor.netty.http.client.HttpClient
import reactor.netty.http.server.HttpServer
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HandshakePayloadLimitTest {
    @Test
    fun `should accept inbound handshake payload at byte limit`(): Unit =
        runBlocking {
            // given
            val server = startInboundHandshakeServer()
            val port =
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            val payload = payloadWithSize(MAX_HANDSHAKE_PAYLOAD_SIZE_BYTES, Json.encodeToString(callbackRequest()))

            try {
                // when
                val status = postHandshake(port, payload, chunked = false)

                // then
                assertEquals(MAX_HANDSHAKE_PAYLOAD_SIZE_BYTES, payload.toByteArray(StandardCharsets.UTF_8).size)
                assertEquals(HttpStatusCode.OK.value, status)
            } finally {
                server.stop()
            }
        }

    @Test
    fun `should reject oversized fixed length inbound handshake payload`(): Unit =
        runBlocking {
            // given
            val server = startInboundHandshakeServer()
            val port =
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            val payload = payloadWithSize(MAX_HANDSHAKE_PAYLOAD_SIZE_BYTES + 1, Json.encodeToString(callbackRequest()))

            try {
                // when
                val status =
                    postHandshake(
                        port = port,
                        payload = payload,
                        chunked = false,
                        transmittedPayload = payload.take(1),
                        completeBody = false,
                    )

                // then
                assertEquals(HttpStatusCode.PayloadTooLarge.value, status)
            } finally {
                server.stop()
            }
        }

    @Test
    fun `should reject oversized chunked inbound handshake payload`(): Unit =
        runBlocking {
            // given
            val server = startInboundHandshakeServer()
            val port =
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            val payload = payloadWithSize(MAX_HANDSHAKE_PAYLOAD_SIZE_BYTES + 1, Json.encodeToString(callbackRequest()))

            try {
                // when
                val status = postHandshake(port, payload, chunked = true, completeBody = false)

                // then
                assertEquals(HttpStatusCode.PayloadTooLarge.value, status)
            } finally {
                server.stop()
            }
        }

    @Test
    fun `should bound concurrent inbound handshakes and release timed out admission`(): Unit =
        runBlocking {
            // given
            val requestStarted = CompletableDeferred<Unit>()
            val server =
                startInboundHandshakeServer(
                    maxConcurrentHandshakes = 1,
                    requestTimeout = 1.seconds,
                    requestStarted = requestStarted,
                )
            val port =
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            val payload = Json.encodeToString(callbackRequest())

            try {
                val stalledRequest =
                    async {
                        postHandshake(
                            port = port,
                            payload = payload,
                            chunked = true,
                            transmittedPayload = payload.take(1),
                            completeBody = false,
                        )
                    }
                requestStarted.await()

                // when
                val rejectedStatus =
                    postHandshake(
                        port = port,
                        payload = payload,
                        chunked = false,
                        transmittedPayload = payload.take(1),
                        completeBody = false,
                    )
                val timedOutStatus = stalledRequest.await()
                val recoveredStatus = postHandshake(port, payload, chunked = false)

                // then
                assertEquals(HttpStatusCode.TooManyRequests.value, rejectedStatus)
                assertEquals(HttpStatusCode.RequestTimeout.value, timedOutStatus)
                assertEquals(HttpStatusCode.OK.value, recoveredStatus)
            } finally {
                server.stop()
            }
        }

    @Test
    fun `should accept bounded callback response`(): Unit =
        runBlocking {
            // given
            val response = challengeResponse()
            val payload = Json.encodeToString(response)
            val server =
                HttpServer
                    .create()
                    .host("127.0.0.1")
                    .port(0)
                    .route { routes ->
                        routes.post("/handshakes") { _, httpResponse ->
                            httpResponse
                                .status(HttpStatusCode.OK.value)
                                .header(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                                .header(
                                    HttpHeaderNames.CONTENT_LENGTH,
                                    payload.toByteArray(StandardCharsets.UTF_8).size.toString(),
                                ).sendString(Mono.just(payload), StandardCharsets.UTF_8)
                        }
                    }.bindNow()
            val publicUri = URI("ws://peer.invalid:${server.port()}")

            try {
                // when
                val result = NettyHandshakeCallbackClient().post(validatedEndpoint(publicUri), callbackRequest(publicUri))

                // then
                assertEquals(HttpStatusCode.OK, result.status)
                assertNotNull(result.response)
                assertEquals(response.node.publicUri, result.response?.node?.publicUri)
            } finally {
                server.disposeNow()
            }
        }

    @Test
    fun `should reject oversized fixed length callback response`(): Unit =
        runBlocking {
            // given
            val payload = "x".repeat(MAX_HANDSHAKE_PAYLOAD_SIZE_BYTES + 1)
            val server =
                HttpServer
                    .create()
                    .host("127.0.0.1")
                    .port(0)
                    .route { routes ->
                        routes.post("/handshakes") { _, response ->
                            response
                                .status(HttpStatusCode.OK.value)
                                .header(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                                .header(
                                    HttpHeaderNames.CONTENT_LENGTH,
                                    payload.toByteArray(StandardCharsets.UTF_8).size.toString(),
                                ).sendString(Mono.just(payload), StandardCharsets.UTF_8)
                        }
                    }.bindNow()
            val publicUri = URI("ws://peer.invalid:${server.port()}")

            try {
                // when
                val failure =
                    runCatching {
                        NettyHandshakeCallbackClient().post(validatedEndpoint(publicUri), callbackRequest(publicUri))
                    }.exceptionOrNull()

                // then
                assertInstanceOf(HandshakePayloadTooLargeException::class.java, failure)
            } finally {
                server.disposeNow()
            }
        }

    @Test
    fun `should reject oversized chunked callback response before completion`(): Unit =
        runBlocking {
            // given
            val server =
                HttpServer
                    .create()
                    .host("127.0.0.1")
                    .port(0)
                    .route { routes ->
                        routes.post("/handshakes") { _, response ->
                            response
                                .status(HttpStatusCode.OK.value)
                                .header(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                                .chunkedTransfer(true)
                                .sendString(
                                    Flux.concat(
                                        Flux.just("x".repeat(MAX_HANDSHAKE_PAYLOAD_SIZE_BYTES), "x"),
                                        Flux.never(),
                                    ),
                                    StandardCharsets.UTF_8,
                                )
                        }
                    }.bindNow()
            val publicUri = URI("ws://peer.invalid:${server.port()}")

            try {
                // when
                val failure =
                    runCatching {
                        NettyHandshakeCallbackClient().post(validatedEndpoint(publicUri), callbackRequest(publicUri))
                    }.exceptionOrNull()

                // then
                assertInstanceOf(HandshakePayloadTooLargeException::class.java, failure)
            } finally {
                server.disposeNow()
            }
        }

    private fun startInboundHandshakeServer(
        maxConcurrentHandshakes: Int = MAX_CONCURRENT_HANDSHAKES,
        requestTimeout: Duration = HANDSHAKE_REQUEST_TIMEOUT,
        requestStarted: CompletableDeferred<Unit>? = null,
    ) = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        val requestControl = HandshakeRequestControl(maxConcurrentHandshakes, requestTimeout)
        routing {
            post("/handshakes") {
                try {
                    when (
                        requestControl.execute(call) { channel ->
                            requestStarted?.complete(Unit)
                            call.receiveHandshakePayload(channel)
                            call.respond(HttpStatusCode.OK)
                        }
                    ) {
                        HandshakeRequestOutcome.COMPLETED -> Unit
                        HandshakeRequestOutcome.CAPACITY_REJECTED -> call.respond(HttpStatusCode.TooManyRequests)
                        HandshakeRequestOutcome.TIMED_OUT -> call.respond(HttpStatusCode.RequestTimeout)
                    }
                } catch (_: HandshakePayloadTooLargeException) {
                    call.respond(HttpStatusCode.PayloadTooLarge)
                }
            }
        }
    }.start(wait = false)

    private suspend fun postHandshake(
        port: Int,
        payload: String,
        chunked: Boolean,
        transmittedPayload: String = payload,
        completeBody: Boolean = true,
    ): Int {
        val payloadSize = payload.toByteArray(StandardCharsets.UTF_8).size
        val chunks = Flux.fromIterable(transmittedPayload.chunked(256))
        val body = if (completeBody) chunks else Flux.concat(chunks, Flux.never<String>())
        val client =
            HttpClient
                .newConnection()
                .headers { headers ->
                    headers.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                    if (chunked) {
                        headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                    } else {
                        headers.set(HttpHeaderNames.CONTENT_LENGTH, payloadSize)
                    }
                }

        return client
            .post()
            .uri("http://127.0.0.1:$port/handshakes")
            .send(ByteBufFlux.fromString(body))
            .responseSingle { response, body -> body.thenReturn(response.status().code()) }
            .awaitSingle()
    }

    private fun payloadWithSize(
        size: Int,
        base: String,
    ): String {
        val baseSize = base.toByteArray(StandardCharsets.UTF_8).size
        require(baseSize <= size)
        return base + " ".repeat(size - baseSize)
    }

    private fun validatedEndpoint(publicUri: URI): ValidatedPeerEndpoint =
        ValidatedPeerEndpoint(publicUri, listOf(InetAddress.getByName("127.0.0.1")))

    private fun callbackRequest(publicUri: URI = URI("ws://peer.invalid:8082")) =
        CounterChallengeResponse(
            challenge = "challenge",
            genesis = AttoHash(ByteArray(32)),
            node = node(publicUri),
            timestamp = AttoInstant.now(),
            signature = AttoSignature(ByteArray(64)),
            counterChallenge = "counter-challenge",
        )

    private fun challengeResponse() =
        ChallengeResponse(
            node = node(URI("ws://peer.example:8082")),
            timestamp = AttoInstant.now(),
            signature = AttoSignature(ByteArray(64)),
        )

    private fun node(publicUri: URI) =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(ByteArray(32)),
            publicUri = publicUri,
            features = emptySet(),
        )
}
