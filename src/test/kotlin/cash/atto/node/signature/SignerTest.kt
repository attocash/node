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
import cash.atto.commons.AttoVersion
import cash.atto.commons.AttoVote
import cash.atto.commons.toAttoAmount
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toSignerBlocking
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import reactor.netty.resources.LoopResources
import java.time.Duration
import kotlin.random.Random

class SignerTest {
    companion object {
        private val signerServer = MockRemoteSigner()

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

    val signer = NettyRemoteSigner("http://localhost:${signerServer.port}", "mock_test")

    @AfterEach
    fun closeSigner() {
        signer.close()
    }

    @Test
    fun `should sign block`(): Unit =
        runBlocking {
            // given
            val block = AttoBlock.sample()

            // when
            val signature = signer.sign(block)

            // then
            assertTrue(signature.isValid(signer.publicKey, block.hash))
        }

    @Test
    fun `should sign vote`(): Unit =
        runBlocking {
            // given
            val block = AttoVote.sample()

            // when
            val signature = signer.sign(block)

            // then
            assertTrue(signature.isValid(signer.publicKey, block.hash))
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
            assertTrue(signature.isValid(signer.publicKey, hash))
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
            network = AttoNetwork.LOCAL,
            hash = AttoHash(Random.Default.nextBytes(32)),
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(32)),
            timestamp = AttoInstant.now(),
            receiverAlgorithm = AttoAlgorithm.V1,
            receiverPublicKey = signer.publicKey,
            amount = 1000UL.toAttoAmount(),
        )

    class MockRemoteSigner {
        val signer = AttoPrivateKey.generate().toSignerBlocking()
        private val loops = LoopResources.create("signer-contract-test-server", 1, true)
        private val timeout = Duration.ofSeconds(5)
        private val json =
            Json {
                encodeDefaults = true
                isLenient = true
                allowSpecialFloatingPointValues = true
                allowStructuredMapKeys = true
                prettyPrint = false
                useArrayPolymorphism = false
            }
        private lateinit var server: DisposableServer

        val port: Int
            get() = server.port()

        fun start() {
            try {
                server =
                    HttpServer
                        .create()
                        .host("localhost")
                        .port(0)
                        .runOn(loops)
                        .route { routes ->
                            routes.get("/public-keys") { _, response ->
                                response.header("Content-Type", "application/json").sendString(
                                    Mono.fromSupplier { json.encodeToString(PublicKeyResponse(signer.publicKey)) },
                                )
                            }
                            routes.post("/blocks") { request, response ->
                                val body =
                                    request.receive().aggregate().asString().flatMap { payload ->
                                        mono {
                                            val target = json.decodeFromString<BlockSignatureRequest>(payload).target
                                            json.encodeToString(SignatureResponse(signer.sign(target)))
                                        }
                                    }
                                response.header("Content-Type", "application/json").sendString(body)
                            }
                            routes.post("/votes") { request, response ->
                                val body =
                                    request.receive().aggregate().asString().flatMap { payload ->
                                        mono {
                                            val target = json.decodeFromString<VoteSignatureRequest>(payload).target
                                            json.encodeToString(SignatureResponse(signer.sign(target)))
                                        }
                                    }
                                response.header("Content-Type", "application/json").sendString(body)
                            }
                            routes.post("/challenges") { request, response ->
                                val body =
                                    request.receive().aggregate().asString().flatMap { payload ->
                                        mono {
                                            val target = json.decodeFromString<ChallengeSignatureRequest>(payload)
                                            json.encodeToString(SignatureResponse(signer.sign(target.target, target.timestamp)))
                                        }
                                    }
                                response.header("Content-Type", "application/json").sendString(body)
                            }
                        }.bindNow(timeout)
            } catch (exception: Exception) {
                disposeLoops()
                throw exception
            }
        }

        fun stop() {
            try {
                server.disposeNow(timeout)
            } finally {
                disposeLoops()
            }
        }

        private fun disposeLoops() {
            loops.disposeLater(Duration.ZERO, timeout).block(timeout.plusSeconds(1))
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
