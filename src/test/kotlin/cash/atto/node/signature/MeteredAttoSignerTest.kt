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
import cash.atto.commons.generate
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

class MeteredAttoSignerTest {
    @Test
    fun `should record successful vote signing latency`() {
        // given
        val meterRegistry = SimpleMeterRegistry()
        val signer = MeteredAttoSigner(TestSigner { AttoSignature(ByteArray(64)) }, meterRegistry)
        val vote = AttoVote.sample(signer.publicKey)

        // when
        runBlocking {
            signer.sign(vote)
        }

        // then
        val timer =
            meterRegistry
                .find("signer.signature.latency")
                .tag("operation", "vote")
                .tag("outcome", "success")
                .timer()
        assertNotNull(timer)
        assertEquals(1, timer!!.count())
    }

    @Test
    fun `should record failed challenge signing latency`() {
        // given
        val meterRegistry = SimpleMeterRegistry()
        val signer =
            MeteredAttoSigner(
                TestSigner { throw IllegalStateException("Failed to sign") },
                meterRegistry,
            )
        val challenge = AttoChallenge.generate()
        val timestamp = AttoInstant.now()

        // when
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                signer.sign(challenge, timestamp)
            }
        }

        // then
        val timer =
            meterRegistry
                .find("signer.signature.latency")
                .tag("operation", "challenge")
                .tag("outcome", "error")
                .timer()
        assertNotNull(timer)
        assertEquals(1, timer!!.count())
    }

    @Test
    fun `should record cancelled block signing latency`() {
        // given
        val meterRegistry = SimpleMeterRegistry()
        val signer =
            MeteredAttoSigner(
                TestSigner { throw CancellationException("Cancelled") },
                meterRegistry,
            )
        val block = AttoBlock.sample(signer.publicKey)

        // when
        assertThrows(CancellationException::class.java) {
            runBlocking {
                signer.sign(block)
            }
        }

        // then
        val timer =
            meterRegistry
                .find("signer.signature.latency")
                .tag("operation", "block")
                .tag("outcome", "cancelled")
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
