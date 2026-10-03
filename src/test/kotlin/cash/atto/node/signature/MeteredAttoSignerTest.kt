package cash.atto.node.signature

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoBlock
import cash.atto.commons.AttoChallenge
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoVote
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

class MeteredAttoSignerTest {
    @Test
    fun `should expose zero signing counters before the first call`() {
        // Given
        val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val delegate = TestSigner { error("No signing expected") }

        // When
        MeteredAttoSigner(delegate, meterRegistry)
        val scrape = meterRegistry.scrape()

        // Then
        val timers = meterRegistry.find("signer.signature.latency").timers()
        assertEquals(18, timers.size)
        timers.forEach { timer ->
            assertEquals(0, timer.count())
            assertEquals(
                setOf("operation", "outcome", "vote_type"),
                timer.id.tags
                    .map { it.key }
                    .toSet(),
            )
        }
        assertEquals(18, scrape.lineSequence().count { it.startsWith("signer_signature_latency_seconds_count{") })
    }

    @Test
    fun `should record final and nonfinal vote signing separately`() {
        // Given
        val meterRegistry = SimpleMeterRegistry()
        val signature = AttoSignature(ByteArray(64))
        var calls = 0
        val signer =
            MeteredAttoSigner(
                TestSigner {
                    calls++
                    signature
                },
                meterRegistry,
            )
        val vote = AttoVote.sample(signer.publicKey)
        val finalVote = vote.copy(timestamp = AttoVote.finalTimestamp)

        // When
        val signatures =
            runBlocking {
                listOf(signer.sign(vote), signer.sign(finalVote), signer.sign(finalVote))
            }

        // Then
        assertEquals(3, calls)
        signatures.forEach { assertSame(signature, it) }
        val nonfinalTimer =
            meterRegistry
                .find("signer.signature.latency")
                .tag("operation", "vote")
                .tag("outcome", "success")
                .tag("vote_type", "nonfinal")
                .timer()
        val finalTimer =
            meterRegistry
                .find("signer.signature.latency")
                .tag("operation", "vote")
                .tag("outcome", "success")
                .tag("vote_type", "final")
                .timer()
        assertNotNull(nonfinalTimer)
        assertNotNull(finalTimer)
        assertEquals(1, nonfinalTimer!!.count())
        assertEquals(2, finalTimer!!.count())
        assertEquals(3, meterRegistry.find("signer.signature.latency").timers().sumOf { it.count() })
    }

    @Test
    fun `should record failed challenge signing latency`() {
        // Given
        val meterRegistry = SimpleMeterRegistry()
        val failure = IllegalStateException("Failed to sign")
        val signer =
            MeteredAttoSigner(
                TestSigner { throw failure },
                meterRegistry,
            )
        val challenge = AttoChallenge.generate()
        val timestamp = AttoInstant.now()

        // When
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    signer.sign(challenge, timestamp)
                }
            }

        // Then
        assertSame(failure, thrown)
        val timer =
            meterRegistry
                .find("signer.signature.latency")
                .tag("operation", "challenge")
                .tag("outcome", "error")
                .tag("vote_type", "not_applicable")
                .timer()
        assertNotNull(timer)
        assertEquals(1, timer!!.count())
    }

    @Test
    fun `should record cancelled block signing latency`() {
        // Given
        val meterRegistry = SimpleMeterRegistry()
        val cancellation = CancellationException("Cancelled")
        val signer =
            MeteredAttoSigner(
                TestSigner { throw cancellation },
                meterRegistry,
            )
        val block = AttoBlock.sample(signer.publicKey)

        // When
        val thrown =
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    signer.sign(block)
                }
            }

        // Then
        assertSame(cancellation, thrown)
        val timer =
            meterRegistry
                .find("signer.signature.latency")
                .tag("operation", "block")
                .tag("outcome", "cancelled")
                .tag("vote_type", "not_applicable")
                .timer()
        assertNotNull(timer)
        assertEquals(1, timer!!.count())
    }

    private class TestSigner(
        private val sign: suspend () -> AttoSignature,
    ) : AttoSigner {
        override val algorithm = AttoAlgorithm.V1
        override val publicKey = AttoPublicKey(Random.nextBytes(32))
        override val address = cash.atto.commons.AttoAddress(algorithm, publicKey)

        override suspend fun sign(hash: AttoHash): AttoSignature = sign()

        override suspend fun sign(block: AttoBlock): AttoSignature = sign()

        override suspend fun sign(vote: AttoVote): AttoSignature = sign()

        override suspend fun sign(
            challenge: AttoChallenge,
            timestamp: AttoInstant,
        ): AttoSignature = sign()
    }

    private fun AttoVote.Companion.sample(publicKey: AttoPublicKey): AttoVote =
        AttoVote(
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = publicKey,
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = AttoHash(Random.nextBytes(ByteArray(32))),
            timestamp = AttoInstant.now(),
        )

    private fun AttoBlock.Companion.sample(publicKey: AttoPublicKey): AttoBlock =
        AttoReceiveBlock(
            version = 0U.toAttoVersion(),
            network = AttoNetwork.LOCAL,
            algorithm = AttoAlgorithm.V1,
            publicKey = publicKey,
            height = 2U.toAttoHeight(),
            balance = AttoAmount.MAX,
            timestamp = AttoInstant.now(),
            previous = AttoHash(Random.nextBytes(ByteArray(32))),
            sendHashAlgorithm = AttoAlgorithm.V1,
            sendHash = AttoHash(Random.Default.nextBytes(ByteArray(32))),
        )
}
