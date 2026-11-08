package cash.atto.node.network

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoChallenge
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.toSignerBlocking
import cash.atto.node.network.guardian.Guardian
import cash.atto.node.network.guardian.InboundConnectionDecision
import cash.atto.node.signature.NettyRemoteSigner
import cash.atto.node.signature.SignerTest.MockRemoteSigner
import cash.atto.node.time.InstantController
import cash.atto.node.transaction.Transaction
import cash.atto.protocol.AttoNode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import reactor.netty.http.HttpResources
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class PeerHttpIsolationTest {
    @Test
    fun `stopping peer listeners and clients leaves the Spring controller operational`() =
        runBlocking {
            // Given
            val context = AnnotationConfigApplicationContext(HttpConfiguration::class.java)
            val httpServer = NettyReactiveWebServerFactory(0).getWebServer(WebHttpHandlerBuilder.applicationContext(context).build())
            val dnsResolver = NetworkDnsResolver()
            val peerClient = PeerNettyClient(dnsResolver)
            val referenceSigner = MockRemoteSigner()
            referenceSigner.start()
            val remoteSigner = NettyRemoteSigner("http://127.0.0.1:${referenceSigner.port}", "isolation-test")
            val signer = AttoPrivateKey.generate().toSignerBlocking()
            val peerPort = ServerSocket(0).use { it.localPort }
            val node =
                AttoNode(
                    network = AttoNetwork.LOCAL,
                    protocolVersion = AttoNode.CURRENT_PROTOCOL_VERSION,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = signer.publicKey,
                    publicUri = URI("ws://127.0.0.1:$peerPort"),
                    features = emptySet(),
                )
            val connectionManager = NodeConnectionManager(node, mockk(relaxed = true), mockk(relaxed = true))
            val genesisHash = AttoHash(ByteArray(32) { 1 })
            val processor =
                NetworkProcessor(
                    genesisTransaction = mockk<Transaction> { every { hash } returns genesisHash },
                    thisNode = node,
                    signer = signer,
                    environment = MockEnvironment().withProperty("websocket.port", peerPort.toString()),
                    networkProperties = NetworkProperties(),
                    peerUriValidator = mockk(),
                    handshakeCallbackService = mockk(),
                    dnsResolver = dnsResolver,
                    connectionManager = connectionManager,
                    guardian =
                        mockk<Guardian> {
                            every { requestInboundConnection(any()) } returns InboundConnectionDecision.Accepted
                        },
                    peerClient = peerClient,
                )
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            httpServer.start()
            val apiUri = URI("http://127.0.0.1:${httpServer.port}")
            val sharedResources = HttpResources.get()
            try {
                assertEquals(200, client.get(apiUri.resolve("/instants/2026-11-07T00:00:00Z")))
                assertEquals(404, client.get(URI("http://127.0.0.1:$peerPort/instants/2026-11-07T00:00:00Z")))
                val callback =
                    CounterChallengeResponse(
                        challenge = "00",
                        genesis = genesisHash,
                        node = node,
                        timestamp = AttoInstant.now(),
                        signature = AttoSignature(ByteArray(64)),
                        counterChallenge = "00",
                    )
                // Initialize the actual peer client against the separate HTTP server.
                assertEquals(404, peerClient.postHandshake(apiUri.resolve("/handshakes"), callback).status.code())
                assertEquals(referenceSigner.signer.publicKey, remoteSigner.publicKey)
                val ioThreads = Thread.getAllStackTraces().keys.filter { it.isAlive }
                for (prefix in listOf("atto-peer-server-io", "atto-peer-client-io", "atto-signer-io")) {
                    val owned = ioThreads.filter { it.name.startsWith(prefix) }
                    assertFalse(owned.isEmpty(), "Expected active private I/O threads for $prefix")
                    assertFalse(owned.any { it.isVirtual }, "Netty socket I/O must stay on its private event loops")
                }

                // When
                processor.stop()
                peerClient.close()

                // Then
                assertFalse(sharedResources.isDisposed)
                val challenge = AttoChallenge.generate()
                val timestamp = AttoInstant.now()
                assertTrue(remoteSigner.sign(challenge, timestamp).isValid(remoteSigner.publicKey, challenge, timestamp))
                assertEquals(200, client.get(apiUri.resolve("/instants/2026-11-07T00:00:00Z")))
                assertEquals(404, client.get(apiUri.resolve("/handshakes")))
                remoteSigner.close()
                assertEquals(200, client.get(apiUri.resolve("/instants/2026-11-07T00:00:00Z")))
            } finally {
                processor.stop()
                peerClient.close()
                connectionManager.stop()
                dnsResolver.close()
                remoteSigner.close()
                referenceSigner.stop()
                client.close()
                httpServer.stop()
                context.close()
            }
        }

    private fun HttpClient.get(uri: URI): Int =
        send(
            HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebFlux
    @Import(InstantController::class)
    class HttpConfiguration
}
