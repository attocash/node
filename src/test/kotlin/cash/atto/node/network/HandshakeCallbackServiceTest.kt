package cash.atto.node.network

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.protocol.AttoNode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.URI

class HandshakeCallbackServiceTest {
    @Test
    fun `should not call callback client for unsafe callback uri`() =
        runTest {
            // given
            val callbackClient = mockk<HandshakeCallbackClient>()
            val service = callbackService(callbackClient = callbackClient)
            var requestBuilt = false
            val unsafeUris =
                listOf(
                    URI("ws://127.0.0.1:7070"),
                    URI("ws://10.0.0.1:7070"),
                    URI("ws://169.254.169.254:80"),
                    URI("ws://224.0.0.1:7070"),
                    URI("ws://192.0.2.10:7070"),
                )

            unsafeUris.forEach { publicUri ->
                // when
                val result =
                    service.post("198.51.100.10", publicUri) {
                        requestBuilt = true
                        mockk()
                    }

                // then
                assertInstanceOf(HandshakeCallbackResult.Rejected::class.java, result)
                assertEquals(HttpStatusCode.BadRequest, (result as HandshakeCallbackResult.Rejected).status)
            }

            assertFalse(requestBuilt)
            coVerify(exactly = 0) { callbackClient.post(any(), any()) }
        }

    @Test
    fun `should pass validated endpoint to callback client`() =
        runTest {
            // given
            val callbackClient = mockk<HandshakeCallbackClient>()
            val request = mockk<CounterChallengeResponse>()
            val publicUri = URI("ws://peer.example:7070")
            val publicAddress = address("93.184.216.34")
            val service = callbackService(callbackClient, mapOf("peer.example" to listOf(publicAddress)))
            coEvery {
                callbackClient.post(
                    match { it.publicUri == publicUri && it.addresses == listOf(publicAddress) },
                    request,
                )
            } returns HandshakeCallbackResult.Completed(HttpStatusCode.OK, null)

            // when
            val result =
                service.post("198.51.100.10", publicUri) {
                    request
                }

            // then
            assertInstanceOf(HandshakeCallbackResult.Completed::class.java, result)
            coVerify(exactly = 1) {
                callbackClient.post(
                    match { it.publicUri == publicUri && it.addresses == listOf(publicAddress) },
                    request,
                )
            }
        }

    @Test
    fun `should bind callback to validated address without resolving hostname again`(): Unit =
        runBlocking {
            // given
            val receivedHost = CompletableDeferred<String?>()
            val receivedBody = CompletableDeferred<String>()
            val server =
                embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    routing {
                        post("/handshakes") {
                            receivedHost.complete(call.request.headers[HttpHeaders.Host])
                            receivedBody.complete(call.receiveText())
                            call.respond(HttpStatusCode.BadRequest)
                        }
                    }
                }.start(wait = false)
            val serverPort =
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            val publicUri = URI("ws://peer.invalid:$serverPort")
            val resolver = mockk<NetworkDnsResolver>()
            val resolutions =
                ArrayDeque(
                    listOf(
                        listOf(address("127.0.0.2"), address("127.0.0.1")),
                        listOf(address("127.0.0.3")),
                    ),
                )
            coEvery { resolver.getAllByName("peer.invalid") } answers { resolutions.removeFirst() }
            val properties = NetworkProperties().apply { loopbackBlocked = false }
            val service =
                HandshakeCallbackService(
                    peerUriValidator = PeerUriValidator(properties, resolver),
                    callbackClient = NettyHandshakeCallbackClient(),
                )

            try {
                // when
                val result = service.post("198.51.100.10", publicUri) { callbackRequest(publicUri) }

                // then
                val completed = assertInstanceOf(HandshakeCallbackResult.Completed::class.java, result)
                assertEquals(HttpStatusCode.BadRequest, completed.status)
                assertEquals("peer.invalid:$serverPort", receivedHost.await())
                assertFalse(receivedBody.await().isBlank())
                assertEquals(1, resolutions.size)
                coVerify(exactly = 1) { resolver.getAllByName("peer.invalid") }
            } finally {
                server.stop()
            }
        }

    private fun callbackService(
        callbackClient: HandshakeCallbackClient,
        addresses: Map<String, List<InetAddress>> = emptyMap(),
    ): HandshakeCallbackService {
        val resolver = mockk<NetworkDnsResolver>()
        coEvery { resolver.getAllByName(any()) } answers {
            addresses[firstArg()] ?: listOf(address(firstArg()))
        }
        return HandshakeCallbackService(
            peerUriValidator = PeerUriValidator(NetworkProperties(), resolver),
            callbackClient = callbackClient,
        )
    }

    private fun address(value: String): InetAddress = InetAddress.getByName(value)

    private fun callbackRequest(publicUri: URI): CounterChallengeResponse =
        CounterChallengeResponse(
            challenge = "challenge",
            genesis = AttoHash(ByteArray(32)),
            node =
                AttoNode(
                    network = AttoNetwork.LOCAL,
                    protocolVersion = 0u,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(ByteArray(32)),
                    publicUri = publicUri,
                    features = emptySet(),
                ),
            timestamp = AttoInstant.now(),
            signature = AttoSignature(ByteArray(64)),
            counterChallenge = "counter-challenge",
        )
}
