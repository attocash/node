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
import cash.atto.commons.toHex
import cash.atto.commons.toSignerBlocking
import cash.atto.node.transaction.Transaction
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI

class HandshakeProtocolTest {
    @Test
    fun `reciprocal challenge exchange authenticates both nodes once`(): Unit =
        runBlocking {
            // Given
            Fixture().use { fixture ->
                val request = fixture.counterResponse()

                // When
                val prepared = fixture.protocol.prepareResponse("peer", request)

                // Then
                assertNotNull(prepared)
                val handshake = requireNotNull(prepared)
                assertEquals(fixture.peerNode.publicUri, handshake.publicUri)
                assertEquals(fixture.peerNode, handshake.node)
                assertEquals(fixture.localNode, handshake.response.node)

                // When
                val authenticated =
                    fixture.peerProtocol.authenticateResponse(
                        "local",
                        fixture.localNode.publicUri,
                        request.counterChallenge,
                        handshake.response,
                    )

                // Then
                assertEquals(fixture.localNode, authenticated)
                assertNull(fixture.protocol.prepareResponse("peer", request))
                assertNull(
                    fixture.peerProtocol.authenticateResponse(
                        "local",
                        fixture.localNode.publicUri,
                        request.counterChallenge,
                        handshake.response,
                    ),
                )
            }
        }

    @ParameterizedTest
    @EnumSource(CounterFailure::class)
    fun `rejected counter response consumes challenge before further authentication`(failure: CounterFailure): Unit =
        runBlocking {
            // Given
            Fixture().use { fixture ->
                val signer = mockk<AttoSigner>()
                val protocol = HandshakeProtocol(fixture.genesisTransaction, fixture.localNode, signer)
                val valid = fixture.counterResponse()
                val rejected =
                    when (failure) {
                        CounterFailure.URI -> valid.copy(node = valid.node.copy(publicUri = URI("ws://other.example:8082")))
                        CounterFailure.GENESIS -> valid.copy(genesis = AttoHash(ByteArray(32) { 4 }))
                        CounterFailure.SIGNATURE -> valid.copy(signature = AttoSignature(ByteArray(64)))
                        CounterFailure.PREFIX -> valid.copy(counterChallenge = fixture.challenge(fixture.peerNode.publicUri))
                    }

                // When
                val result = protocol.prepareResponse("peer", rejected)
                val replay = protocol.prepareResponse("peer", valid)

                // Then
                assertNull(result)
                assertNull(replay)
                coVerify(exactly = 0) { signer.sign(any<AttoChallenge>(), any()) }
            }
        }

    @ParameterizedTest
    @EnumSource(ResponseFailure::class)
    fun `rejected callback response consumes counter challenge`(failure: ResponseFailure): Unit =
        runBlocking {
            // Given
            Fixture().use { fixture ->
                val request = fixture.counterResponse()
                val prepared = requireNotNull(fixture.protocol.prepareResponse("peer", request))
                val valid = prepared.response
                val rejected =
                    when (failure) {
                        ResponseFailure.URI -> valid.copy(node = valid.node.copy(publicUri = URI("ws://other.example:8082")))
                        ResponseFailure.SIGNATURE -> valid.copy(signature = AttoSignature(ByteArray(64)))
                    }

                // When
                val result =
                    fixture.peerProtocol.authenticateResponse("local", fixture.localNode.publicUri, request.counterChallenge, rejected)
                val replay =
                    fixture.peerProtocol.authenticateResponse("local", fixture.localNode.publicUri, request.counterChallenge, valid)

                // Then
                assertNull(result)
                assertNull(replay)
            }
        }

    @Test
    fun `challenge prefix requires local uri followed by random challenge bytes`() {
        // Given
        Fixture().use { fixture ->
            val localChallenge = fixture.challenge(fixture.localNode.publicUri)
            val peerChallenge = fixture.challenge(fixture.peerNode.publicUri)
            val randomOnly = ByteArray(ChallengeStore.CHALLENGE_SIZE).toHex()

            // When
            val localAccepted = fixture.protocol.isChallengePrefixValid(localChallenge)
            val peerAccepted = fixture.protocol.isChallengePrefixValid(peerChallenge)
            val randomAccepted = fixture.protocol.isChallengePrefixValid(randomOnly)

            // Then
            assertTrue(localAccepted)
            assertFalse(peerAccepted)
            assertFalse(randomAccepted)
        }
    }

    @Test
    fun `signing cancellation propagates after challenge consumption`(): Unit =
        runBlocking {
            // Given
            Fixture().use { fixture ->
                val signer = mockk<AttoSigner>()
                val cancelled = CancellationException("Signing cancelled")
                coEvery { signer.sign(any<AttoChallenge>(), any()) } throws cancelled
                val protocol = HandshakeProtocol(fixture.genesisTransaction, fixture.localNode, signer)
                val request = fixture.counterResponse()

                // When
                val failure =
                    try {
                        protocol.prepareResponse("peer", request)
                        fail<CancellationException>("Expected signing cancellation")
                    } catch (exception: CancellationException) {
                        exception
                    }

                // Then
                assertSame(cancelled, failure)
                assertNull(protocol.prepareResponse("peer", request))
                coVerify(exactly = 1) { signer.sign(any<AttoChallenge>(), any()) }
            }
        }

    enum class CounterFailure {
        URI,
        GENESIS,
        SIGNATURE,
        PREFIX,
    }

    enum class ResponseFailure {
        URI,
        SIGNATURE,
    }

    private class Fixture : AutoCloseable {
        private val challenges = mutableListOf<String>()
        private val localSigner = AttoPrivateKey.generate().toSignerBlocking()
        private val peerSigner = AttoPrivateKey.generate().toSignerBlocking()
        val genesisTransaction = mockk<Transaction> { every { hash } returns AttoHash(ByteArray(32) { 3 }) }
        val localNode = node(localSigner.publicKey, URI("ws://local.example:8082"))
        val peerNode = node(peerSigner.publicKey, URI("ws://peer.example:8082"))
        val protocol = HandshakeProtocol(genesisTransaction, localNode, localSigner)
        val peerProtocol = HandshakeProtocol(genesisTransaction, peerNode, peerSigner)

        suspend fun counterResponse(): CounterChallengeResponse =
            peerProtocol.createCounterResponse(challenge(peerNode.publicUri), challenge(localNode.publicUri), AttoInstant.now())

        fun challenge(publicUri: URI): String = ChallengeStore.generate(publicUri).also(challenges::add)

        override fun close() {
            challenges.forEach(ChallengeStore::remove)
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
    }
}
