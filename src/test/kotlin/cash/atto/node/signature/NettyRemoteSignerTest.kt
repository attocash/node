package cash.atto.node.signature

import cash.atto.commons.AttoChallenge
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.toSignerBlocking
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer
import reactor.netty.http.server.HttpServerRequest
import reactor.netty.http.server.HttpServerResponse
import reactor.netty.resources.LoopResources
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class NettyRemoteSignerTest {
    private val local = AttoPrivateKey.generate().toSignerBlocking()
    private val keyJson = Json.encodeToString(SignerTest.PublicKeyResponse(local.publicKey))

    @Test
    fun `public key is lazy cached accepts unknown fields and forwards exact authorization`() {
        // Given
        val requests = AtomicInteger()
        SignerServer { request, response ->
            requests.incrementAndGet()
            assertEquals("opaque token", request.requestHeaders().get("Authorization"))
            assertEquals("application/json", request.requestHeaders().get("Accept"))
            assertEquals("/prefix/public-keys", request.uri())
            response.sendString(Mono.just(keyJson.dropLast(1) + ",\"future\":true}"))
        }.use { server ->
            NettyRemoteSigner("${server.url}/prefix/", "opaque token").use { signer ->
                assertEquals(0, requests.get())

                // When
                val first = signer.publicKey
                val second = signer.publicKey

                // Then
                assertEquals(local.publicKey, first)
                assertEquals(first, second)
                assertEquals(1, requests.get())
                assertTrue(signer.address.publicKey == first)
            }
        }
    }

    @Test
    fun `retries timeout throttling and malformed JSON then caches the successful public key`() {
        // Given
        val requests = AtomicInteger()
        SignerServer { _, response ->
            when (requests.incrementAndGet()) {
                1 -> response.status(408).send()
                2 -> response.status(429).send()
                3 -> response.sendString(Mono.just("{"))
                else -> response.sendString(Mono.just(keyJson))
            }
        }.use { server ->
            NettyRemoteSigner(server.url, "test").use { signer ->
                // When
                val key = signer.publicKey

                // Then
                assertEquals(local.publicKey, key)
                assertEquals(4, requests.get())
            }
        }
    }

    @Test
    fun `client and server HTTP errors are terminal except 408 and 429`() {
        // Given
        for (status in listOf(400, 401, 403, 404, 500, 503)) {
            val requests = AtomicInteger()
            SignerServer { _, response ->
                requests.incrementAndGet()
                response.status(status).send()
            }.use { server ->
                NettyRemoteSigner(server.url, "test").use { signer ->
                    // When
                    val error = assertThrows(RemoteSignerTerminalException::class.java) { signer.publicKey }

                    // Then
                    assertEquals(status, error.status)
                    assertEquals(1, requests.get())
                }
            }
        }
    }

    @Test
    fun `terminal HTTP status closes an unfinished error body without waiting or retrying`(): Unit =
        runBlocking {
            // Given
            val requests = AtomicInteger()
            val disconnected = CompletableDeferred<Unit>()
            SignerServer { request, response ->
                requests.incrementAndGet()
                request.withConnection { it.onDispose().doFinally { disconnected.complete(Unit) }.subscribe() }
                response.status(401).sendString(Flux.concat(Mono.just("{"), Flux.never()))
            }.use { server ->
                NettyRemoteSigner(server.url, "test").use { signer ->
                    // When
                    val result = runCatching { withTimeout(5.seconds) { signer.sign(AttoChallenge.generate(), AttoInstant.now()) } }

                    // Then
                    val error = result.exceptionOrNull()
                    assertTrue(error is RemoteSignerTerminalException, "$result")
                    assertEquals(401, (error as RemoteSignerTerminalException).status)
                    assertEquals(1, requests.get())
                    withTimeout(5.seconds) { disconnected.await() }
                }
            }
        }

    @Test
    fun `rejects invalid signatures without retrying the accepted signature response`() =
        runBlocking {
            // Given
            val signatures = AtomicInteger()
            SignerServer { request, response ->
                if (request.uri() == "/public-keys") {
                    response.sendString(Mono.just(keyJson))
                } else {
                    signatures.incrementAndGet()
                    response.sendString(Mono.just(Json.encodeToString(SignerTest.SignatureResponse(AttoSignature(ByteArray(64))))))
                }
            }.use { server ->
                NettyRemoteSigner(server.url, "test").use { signer ->
                    // When
                    val result = runCatching { withTimeout(5.seconds) { signer.sign(AttoChallenge.generate(), AttoInstant.now()) } }

                    // Then
                    assertTrue(result.exceptionOrNull() is RemoteSignerInvalidSignatureException, "$result")
                    assertEquals(1, signatures.get())
                }
            }
        }

    @Test
    fun `successful signing releases connections across more requests than the pool capacity`() =
        runBlocking {
            // Given
            val challenge = AttoChallenge.generate()
            val timestamp = AttoInstant.now()
            val signature = local.sign(challenge, timestamp)
            val signatureJson = Json.encodeToString(SignerTest.SignatureResponse(signature))
            val requests = AtomicInteger()
            SignerServer { request, response ->
                requests.incrementAndGet()
                val body = if (request.uri() == "/public-keys") keyJson else signatureJson
                request.receive().then(response.sendString(Mono.just(body)).then())
            }.use { server ->
                NettyRemoteSigner(server.url, "test").use { signer ->
                    // When
                    withTimeout(15.seconds) {
                        repeat(128) { assertEquals(signature, signer.sign(challenge, timestamp)) }
                    }

                    // Then
                    assertEquals(129, requests.get())
                }
            }
        }

    @Test
    fun `read inactivity timeout closes the stalled response and retries signing`() =
        runBlocking {
            // Given
            val challenge = AttoChallenge.generate()
            val timestamp = AttoInstant.now()
            val signature = local.sign(challenge, timestamp)
            val signatureJson = Json.encodeToString(SignerTest.SignatureResponse(signature))
            val requests = AtomicInteger()
            val disconnected = CompletableDeferred<Unit>()
            SignerServer { request, response ->
                if (request.uri() == "/public-keys") {
                    response.sendString(Mono.just(keyJson))
                } else if (requests.incrementAndGet() == 1) {
                    request.withConnection { it.onDispose().doFinally { disconnected.complete(Unit) }.subscribe() }
                    response.sendString(Flux.concat(Mono.just("{"), Flux.never()))
                } else {
                    response.sendString(Mono.just(signatureJson))
                }
            }.use { server ->
                NettyRemoteSigner(server.url, "test").use { signer ->
                    // When
                    val result = withTimeout(8.seconds) { signer.sign(challenge, timestamp) }

                    // Then
                    assertEquals(signature, result)
                    withTimeout(5.seconds) { disconnected.await() }
                    assertEquals(2, requests.get())
                }
            }
        }

    @Test
    fun `cancellation closes a stalled HTTP body and does not retry`() =
        runBlocking {
            // Given
            val started = CompletableDeferred<Unit>()
            val disconnected = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            SignerServer { request, response ->
                requests.incrementAndGet()
                request.withConnection { it.onDispose().doFinally { disconnected.complete(Unit) }.subscribe() }
                response.sendString(Flux.concat(Mono.just("{").doOnNext { started.complete(Unit) }, Flux.never()))
            }.use { server ->
                NettyRemoteSigner(server.url, "test").use { signer ->
                    val signing = async { signer.sign(AttoChallenge.generate(), AttoInstant.now()) }
                    withTimeout(5.seconds) { started.await() }

                    // When
                    signing.cancelAndJoin()

                    // Then
                    withTimeout(5.seconds) { disconnected.await() }
                    assertTrue(signing.isCancelled)
                    assertEquals(1, requests.get())
                }
            }
        }

    @Test
    fun `direct hashes and signed messages retain the unsupported remote API contract`() =
        runBlocking {
            // Given
            val requests = AtomicInteger()
            SignerServer { _, response ->
                requests.incrementAndGet()
                response.sendString(Mono.just(keyJson))
            }.use { server ->
                NettyRemoteSigner(server.url, "test").use { signer ->
                    // When
                    val direct = runCatching { signer.sign(AttoHash(ByteArray(32))) }
                    val message = runCatching { signer.signMessage(byteArrayOf(1)) }

                    // Then
                    assertTrue(direct.exceptionOrNull() is NotImplementedError)
                    assertTrue(message.exceptionOrNull() is NotImplementedError)
                    assertEquals(1, requests.get())
                }
            }
        }

    @Test
    fun `closing metered signer cancels active signing and closes its owned transport`(): Unit =
        runBlocking {
            // Given
            val started = CompletableDeferred<Unit>()
            SignerServer { _, response ->
                response.sendString(Flux.concat(Mono.just("{").doOnNext { started.complete(Unit) }, Flux.never()))
            }.use { server ->
                val remote = NettyRemoteSigner(server.url, "test")
                val signer = MeteredAttoSigner(remote, SimpleMeterRegistry())
                val signing = async { signer.sign(AttoChallenge.generate(), AttoInstant.now()) }
                withTimeout(5.seconds) { started.await() }

                // When
                signer.close()
                signer.close()
                withTimeout(5.seconds) { signing.join() }

                // Then
                assertTrue(signing.isCancelled)
                assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("atto-signer-io") })
                assertThrows(IllegalStateException::class.java) { remote.publicKey }
            }
        }

    private class SignerServer(
        handler: (HttpServerRequest, HttpServerResponse) -> Publisher<Void>,
    ) : AutoCloseable {
        private val loops = LoopResources.create("signer-test-server", 1, true)
        private val server =
            HttpServer
                .create()
                .host("127.0.0.1")
                .port(0)
                .runOn(loops)
                .handle(handler)
                .bindNow()
        val url = "http://127.0.0.1:${server.port()}"

        override fun close() {
            server.disposeNow()
            loops.disposeLater(Duration.ZERO, Duration.ofSeconds(5)).block(Duration.ofSeconds(6))
        }
    }
}
