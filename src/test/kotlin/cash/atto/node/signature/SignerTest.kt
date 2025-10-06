package cash.atto.node.signature

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoBlock
import cash.atto.commons.AttoChallenge
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoVersion
import cash.atto.commons.AttoVote
import cash.atto.commons.generate
import cash.atto.commons.isValid
import cash.atto.commons.node.remote
import cash.atto.commons.toAttoAmount
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toByteArray
import cash.atto.commons.toSigner
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SignerTest {
    companion object {
        val signerServer = MocktRemoteSigner(9999)

        @JvmStatic
        @BeforeAll
        fun start() {
            signerServer.start()
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            signerServer.stop()
        }
    }

    val signer =
        AttoSigner.remote("http://localhost:9999") {
            mapOf("Authorization" to "mock_test")
        }

    @Test
    fun `should sign block`(): Unit =
        runBlocking {
            // given
            val block = AttoBlock.sample()

            // when
            val signature = signer.sign(block)

            // then
            assertTrue { signature.isValid(signer.publicKey, block.hash) }
        }

    @Test
    fun `should sign vote`(): Unit =
        runBlocking {
            // given
            val block = AttoVote.sample()

            // when
            val signature = signer.sign(block)

            // then
            assertTrue { signature.isValid(signer.publicKey, block.hash) }
        }

    @Test
    fun `should sign challenge`(): Unit =
        runBlocking {
            // given
            val challenge = AttoChallenge.generate()
            val timestamp = AttoInstant.now()

            // when
            val signature = signer.sign(challenge, timestamp)

            // then
            val hash = AttoHash.hash(64, signer.publicKey.value, challenge.value, timestamp.toByteArray())
            assertTrue { signature.isValid(signer.publicKey, hash) }
        }

    private fun AttoVote.Companion.sample(): AttoVote =
        AttoVote(
            version = AttoVersion(0U),
            algorithm = AttoAlgorithm.V1,
            publicKey = signer.publicKey,
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = AttoHash(Random.nextBytes(ByteArray(32))),
            timestamp = AttoInstant.now(),
        )

    private fun AttoBlock.Companion.sample(): AttoBlock =
        AttoReceiveBlock(
            version = 0U.toAttoVersion(),
            network = AttoNetwork.LOCAL,
            algorithm = AttoAlgorithm.V1,
            publicKey = signer.publicKey,
            height = 2U.toAttoHeight(),
            balance = AttoAmount.MAX,
            timestamp = AttoInstant.now(),
            previous = AttoHash(Random.nextBytes(ByteArray(32))),
            sendHashAlgorithm = AttoAlgorithm.V1,
            sendHash = AttoHash(Random.Default.nextBytes(ByteArray(32))),
        )

    private fun AttoReceivable.Companion.sample(): AttoReceivable =
        AttoReceivable(
            hash = AttoHash(Random.Default.nextBytes(32)),
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(32)),
            timestamp = AttoInstant.now(),
            receiverAlgorithm = AttoAlgorithm.V1,
            receiverPublicKey = signer.publicKey,
            amount = 1000UL.toAttoAmount(),
        )

    class MocktRemoteSigner(
        port: Int,
    ) {
        val signer = AttoPrivateKey.generate().toSigner()

        val server =
            embeddedServer(CIO, port = port) {
                install(ContentNegotiation) {
                    json()
                }

                routing {
                    get("/public-keys") {
                        val response = PublicKeyResponse(signer.publicKey)
                        call.respond(response)
                    }

                    post("/blocks") {
                        val request = call.request.call.receive<BlockSignatureRequest>()
                        val signature = signer.sign(request.target)
                        call.respond(SignatureResponse(signature))
                    }

                    post("/votes") {
                        val request = call.request.call.receive<VoteSignatureRequest>()
                        val signature = signer.sign(request.target)
                        call.respond(SignatureResponse(signature))
                    }

                    post("/challenges") {
                        val request = call.request.call.receive<ChallengeSignatureRequest>()
                        val signature = signer.sign(request.target, request.timestamp)
                        call.respond(SignatureResponse(signature))
                    }
                }
            }

        fun start() {
            server.start()
        }

        fun stop() {
            server.stop()
        }
    }

    @Serializable
    data class PublicKeyResponse(
        val publicKey: AttoPublicKey,
    )

    interface SignatureRequest<T> {
        val target: T
    }

    @Serializable
    data class BlockSignatureRequest(
        override val target: AttoBlock,
    ) : SignatureRequest<AttoBlock>

    @Serializable
    data class VoteSignatureRequest(
        override val target: AttoVote,
    ) : SignatureRequest<AttoVote>

    @Serializable
    data class ChallengeSignatureRequest(
        override val target: AttoChallenge,
        val timestamp: AttoInstant,
    ) : SignatureRequest<AttoChallenge>

    @Serializable
    data class SignatureResponse(
        val signature: AttoSignature,
    )
}
