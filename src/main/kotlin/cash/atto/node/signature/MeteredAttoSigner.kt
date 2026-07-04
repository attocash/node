package cash.atto.node.signature

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoBlock
import cash.atto.commons.AttoChallenge
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoVote
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

internal class MeteredAttoSigner(
    internal val delegate: AttoSigner,
    private val meterRegistry: MeterRegistry,
) : AttoSigner {
    private val timers = ConcurrentHashMap<String, Timer>()

    override val algorithm: AttoAlgorithm
        get() = delegate.algorithm
    override val publicKey: AttoPublicKey
        get() = delegate.publicKey
    override val address: AttoAddress
        get() = delegate.address

    override suspend fun sign(hash: AttoHash): AttoSignature =
        record("hash") {
            delegate.sign(hash)
        }

    override suspend fun sign(block: AttoBlock): AttoSignature =
        record("block") {
            delegate.sign(block)
        }

    override suspend fun sign(vote: AttoVote): AttoSignature =
        record("vote") {
            delegate.sign(vote)
        }

    override suspend fun sign(
        challenge: AttoChallenge,
        timestamp: AttoInstant,
    ): AttoSignature =
        record("challenge") {
            delegate.sign(challenge, timestamp)
        }

    override suspend fun signMessage(message: ByteArray): AttoSignature =
        record("message") {
            delegate.signMessage(message)
        }

    private suspend fun <T> record(
        operation: String,
        action: suspend () -> T,
    ): T {
        val started = System.nanoTime()
        var outcome = "success"
        try {
            return action()
        } catch (e: CancellationException) {
            outcome = "cancelled"
            throw e
        } catch (e: Exception) {
            outcome = "error"
            throw e
        } finally {
            timer(operation, outcome).record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
        }
    }

    private fun timer(
        operation: String,
        outcome: String,
    ): Timer =
        timers.computeIfAbsent("$operation:$outcome") {
            Timer
                .builder("signer.signature.latency")
                .description("Time taken to sign with this node signer")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meterRegistry)
        }
}
