package cash.atto.node.network

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoChallenge
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
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
import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.mock.env.MockEnvironment
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.LoopResources
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

class InboundPeerAuthenticationTest {
    @ParameterizedTest
    @EnumSource(CallbackFailure::class)
    fun `failed inbound authentication closes the socket and permits an immediate authenticated retry`(failure: CallbackFailure): Unit =
        runBlocking {
            // Given
            RunningServer().use { server ->
                server.failCallback(failure)

                withTimeout(4.seconds) {
                    // When
                    server.connectAndAwaitClose()

                    // Then
                    coVerify(exactly = 1) { server.callback.post(any(), server.peerNode.publicUri, any()) }
                    coVerify(exactly = 0) { server.connections.manage(any(), any(), any()) }

                    // When: retry the same URI before the five-second reservation expires.
                    server.acceptCallback()
                    server.connectAndAwaitClose()

                    // Then
                    coVerify(exactly = 2) { server.callback.post(any(), server.peerNode.publicUri, any()) }
                    coVerify(exactly = 1) { server.connections.manage(server.peerNode, any(), any()) }

                    // When: an authenticated URI retains its reservation until the existing expiry.
                    server.connectAndAwaitClose()

                    // Then
                    coVerify(exactly = 2) { server.callback.post(any(), server.peerNode.publicUri, any()) }
                    coVerify(exactly = 1) { server.connections.manage(any(), any(), any()) }
                }
            }
        }

    enum class CallbackFailure {
        REJECTED_URI,
        CONNECTION_FAILURE,
        INVALID_SIGNATURE,
    }

    private class RunningServer : AutoCloseable {
        private val port = freePort()
        private val signer = AttoPrivateKey.generate().toSignerBlocking()
        private val peerSigner = AttoPrivateKey.generate().toSignerBlocking()
        private val localNode = node(signer.publicKey, URI("ws://127.0.0.1:$port"))
        private val genesis = AttoHash(ByteArray(32) { 3 })
        private val counterChallenges = ConcurrentLinkedQueue<String>()
        private val resolver = NetworkDnsResolver()
        private val peerClient = PeerNettyClient(resolver)
        private val clientLoops = LoopResources.create("inbound-peer-auth-test", 1, true)
        private val client = HttpClient.newConnection().runOn(clientLoops).disableRetry(true)
        val peerNode = node(peerSigner.publicKey, URI("ws://127.0.0.1:${freePort()}"))
        val callback = mockk<HandshakeCallbackService>()
        val connections = mockk<NodeConnectionManager>(relaxed = true)
        private val processor =
            NetworkProcessor(
                genesisTransaction = mockk<Transaction> { every { hash } returns genesis },
                thisNode = localNode,
                signer = signer,
                environment = MockEnvironment().withProperty("websocket.port", port.toString()),
                networkProperties = NetworkProperties(),
                peerUriValidator = mockk(),
                handshakeCallbackService = callback,
                dnsResolver = resolver,
                connectionManager = connections,
                guardian =
                    mockk<Guardian> {
                        every { requestInboundConnection(any()) } returns InboundConnectionDecision.Accepted
                    },
                peerClient = peerClient,
            )

        fun failCallback(failure: CallbackFailure) {
            coEvery { callback.post(any(), peerNode.publicUri, any()) } coAnswers {
                if (failure == CallbackFailure.REJECTED_URI) {
                    HandshakeCallbackResult.Rejected(HttpResponseStatus.BAD_REQUEST)
                } else {
                    val request = thirdArg<suspend () -> CounterChallengeResponse>().invoke()
                    counterChallenges.add(request.counterChallenge)
                    if (failure == CallbackFailure.CONNECTION_FAILURE) {
                        throw IOException("Peer closed the callback connection")
                    }
                    HandshakeCallbackResult.Completed(
                        HttpResponseStatus.OK,
                        ChallengeResponse(peerNode, AttoInstant.now(), AttoSignature(ByteArray(64))),
                    )
                }
            }
        }

        fun acceptCallback() {
            coEvery { callback.post(any(), peerNode.publicUri, any()) } coAnswers {
                val request = thirdArg<suspend () -> CounterChallengeResponse>().invoke()
                counterChallenges.add(request.counterChallenge)
                assertEquals(genesis, request.genesis)
                assertEquals(localNode, request.node)
                val timestamp = AttoInstant.now()
                HandshakeCallbackResult.Completed(
                    HttpResponseStatus.OK,
                    ChallengeResponse(
                        peerNode,
                        timestamp,
                        peerSigner.sign(AttoChallenge(request.counterChallenge.fromHexToByteArray()), timestamp),
                    ),
                )
            }
        }

        suspend fun connectAndAwaitClose() {
            val random = ByteArray(ChallengeStore.CHALLENGE_SIZE).also { SecureRandom().nextBytes(it) }
            val challenge = (localNode.publicUri.toString().toByteArray() + random).toHex()
            client
                .headers {
                    it.set(NetworkProcessor.PUBLIC_URI_HEADER, peerNode.publicUri.toString())
                    it.set(NetworkProcessor.CHALLENGE_HEADER, challenge)
                }.websocket()
                .uri("ws://127.0.0.1:$port/")
                .handle { inbound, _ -> inbound.receive().then() }
                .then()
                .awaitSingleOrNull()
        }

        override fun close() {
            try {
                processor.stop()
            } finally {
                try {
                    peerClient.close()
                    resolver.close()
                } finally {
                    try {
                        clientLoops.disposeLater(Duration.ZERO, Duration.ofSeconds(5)).block(Duration.ofSeconds(6))
                    } finally {
                        counterChallenges.forEach(ChallengeStore::remove)
                    }
                }
            }
        }

        companion object {
            private fun freePort(): Int = ServerSocket(0).use { it.localPort }

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
        }
    }
}
