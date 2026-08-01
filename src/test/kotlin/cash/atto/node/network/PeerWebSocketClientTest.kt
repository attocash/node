package cash.atto.node.network

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class PeerWebSocketClientTest {
    @Test
    fun `should not follow WebSocket redirects`(): Unit =
        runBlocking {
            // given
            val targetRequests = AtomicInteger()
            val targetServer =
                embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    routing {
                        get("/") {
                            targetRequests.incrementAndGet()
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }.start(wait = false)
            val targetPort =
                targetServer.engine
                    .resolvedConnectors()
                    .single()
                    .port
            val redirectRequests = AtomicInteger()
            val redirectServer =
                embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    routing {
                        get("/") {
                            redirectRequests.incrementAndGet()
                            call.respondRedirect("http://127.0.0.1:$targetPort/")
                        }
                    }
                }.start(wait = false)
            val redirectPort =
                redirectServer.engine
                    .resolvedConnectors()
                    .single()
                    .port
            val endpoint = validatedEndpoint(URI("ws://peer.invalid:$redirectPort"))
            val client = NettyPeerWebSocketClient()

            try {
                // when
                val result = runCatching { client.connect(endpoint, emptyMap()) }
                result.getOrNull()?.session?.close()

                // then
                assertTrue(result.isFailure)
                assertEquals(1, redirectRequests.get())
                assertEquals(0, targetRequests.get())
            } finally {
                redirectServer.stop()
                targetServer.stop()
            }
        }

    @Test
    fun `should use validated address without resolving hostname again`(): Unit =
        runBlocking {
            // given
            val receivedHeader = CompletableDeferred<String?>()
            val receivedHost = CompletableDeferred<String?>()
            val server =
                embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    install(WebSockets)
                    routing {
                        webSocket("/") {
                            receivedHeader.complete(call.request.headers[NetworkProcessor.PUBLIC_URI_HEADER])
                            receivedHost.complete(call.request.headers[HttpHeaders.Host])
                            val frame = incoming.receive() as Frame.Binary
                            val bytes = frame.readBytes()
                            outgoing.send(Frame.Text("ignored"))
                            outgoing.send(Frame.Binary(true, bytes))
                        }
                    }
                }.start(wait = false)
            val serverPort =
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            val resolver = mockk<NetworkDnsResolver>()
            val validatedAddress = address("127.0.0.1")
            val reboundAddress = address("127.0.0.2")
            val resolutions =
                ArrayDeque(
                    listOf(
                        listOf(reboundAddress, validatedAddress),
                        listOf(address("127.0.0.3")),
                    ),
                )
            coEvery { resolver.getAllByName("peer.invalid") } answers { resolutions.removeFirst() }
            val endpoint = validatedEndpoint(URI("ws://peer.invalid:$serverPort"), resolver)
            val client = NettyPeerWebSocketClient()
            val message = byteArrayOf(1, 2, 3)

            try {
                // when
                val connection =
                    client.connect(
                        endpoint,
                        mapOf(NetworkProcessor.PUBLIC_URI_HEADER to "ws://local.example:7070"),
                    )
                connection.session.send(message)
                val response = connection.session.incoming.first()

                // then
                assertArrayEquals(message, response)
                assertEquals(validatedAddress, connection.remoteAddress.address)
                assertEquals("ws://local.example:7070", receivedHeader.await())
                assertEquals("peer.invalid:$serverPort", receivedHost.await())
                assertEquals(1, resolutions.size)
                coVerify(exactly = 1) { resolver.getAllByName("peer.invalid") }

                connection.session.close()
            } finally {
                server.stop()
            }
        }

    private suspend fun validatedEndpoint(
        publicUri: URI,
        resolver: NetworkDnsResolver? = null,
    ): ValidatedPeerEndpoint {
        val endpointResolver =
            resolver ?: mockk<NetworkDnsResolver>().also {
                coEvery { it.getAllByName(any()) } returns listOf(address("127.0.0.1"))
            }
        val properties = NetworkProperties().apply { loopbackBlocked = false }
        val result = PeerUriValidator(properties, endpointResolver).validate(publicUri)
        return (result as PeerUriValidationResult.Accepted).endpoint
    }

    private fun address(value: String): InetAddress = InetAddress.getByName(value)
}
