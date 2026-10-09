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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class NetworkProcessorTest {
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
                    connectionManager = mockk(relaxed = true),
                    guardian =
                        mockk<Guardian> {
                            every { requestInboundConnection(any()) } returns InboundConnectionDecision.Accepted
                        },
                )
            val client =
                HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json()
                    }
                }
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
                assertEquals(HttpStatusCode.RequestTimeout, response.status)
                assertEquals(1, signerInvocations.get())
                assertTrue(signingAttempts.get() > 1)

                // When: a malformed challenge is rejected before invoking the signer again.
                val rejectedResponse =
                    withTimeout(2.seconds) {
                        client.postHandshake(port, request.copy(challenge = "00"))
                    }

                // Then
                assertEquals(HttpStatusCode.BadRequest, rejectedResponse.status)
                coVerify(exactly = 1) { signer.sign(any<AttoChallenge>(), any<AttoInstant>()) }
            } finally {
                cleanup(client, processor, challenge)
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

    private suspend fun HttpClient.postHandshake(
        port: Int,
        request: CounterChallengeResponse,
    ): HttpResponse =
        post("http://127.0.0.1:$port/handshakes") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private fun cleanup(
        client: HttpClient,
        processor: NetworkProcessor,
        challenge: String?,
    ) {
        try {
            client.close()
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
}
