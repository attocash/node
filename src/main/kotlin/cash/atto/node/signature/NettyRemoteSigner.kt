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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/** Remote signing has its own I/O loops and virtual threads, including retries and JSON/cryptographic work. */
class NettyRemoteSigner(
    url: String,
    token: String,
) : AttoSigner,
    AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }
    private val transport = RemoteSignerHttpTransport(url, token)

    override val algorithm: AttoAlgorithm = AttoAlgorithm.V1
    override val publicKey: AttoPublicKey by lazy {
        runBlocking {
            transport.operation {
                retrying("get publicKey") { request<PublicKeyResponse>("public-keys", null, 5).publicKey }
            }
        }
    }
    override val address: AttoAddress by lazy { AttoAddress(algorithm, publicKey) }

    override suspend fun sign(hash: AttoHash): AttoSignature = throw NotImplementedError("Remote signer doesn't accept direct hash signing")

    override suspend fun signMessage(message: ByteArray): AttoSignature = transport.operation { super<AttoSigner>.signMessage(message) }

    override suspend fun sign(block: AttoBlock): AttoSignature =
        transport.operation {
            checkPublicKey(block.publicKey)
            val signature =
                retrying("sign $block") {
                    request<SignatureResponse>("blocks", json.encodeToString(BlockSignatureRequest(block)), 1).signature
                }
            if (!signature.isValid(block.publicKey, block.hash)) throw RemoteSignerInvalidSignatureException("block")
            signature
        }

    override suspend fun sign(vote: AttoVote): AttoSignature =
        transport.operation {
            checkPublicKey(vote.publicKey)
            val signature =
                retrying("sign $vote") {
                    request<SignatureResponse>("votes", json.encodeToString(VoteSignatureRequest(vote)), 1).signature
                }
            if (!signature.isValid(vote.publicKey, vote.hash)) throw RemoteSignerInvalidSignatureException("vote")
            signature
        }

    override suspend fun sign(
        challenge: AttoChallenge,
        timestamp: AttoInstant,
    ): AttoSignature =
        transport.operation {
            val signature =
                retrying("sign $challenge") {
                    val payload = json.encodeToString(ChallengeSignatureRequest(challenge, timestamp))
                    request<SignatureResponse>("challenges", payload, 1).signature
                }
            if (!signature.isValid(publicKey, challenge, timestamp)) throw RemoteSignerInvalidSignatureException("challenge")
            signature
        }

    private suspend inline fun <reified T> request(
        path: String,
        payload: String?,
        timeoutSeconds: Long,
    ): T {
        val response = transport.request(path, payload?.encodeToByteArray(), timeoutSeconds)
        if (response.status >= 300) throw RemoteSignerHttpException(response.status)
        return json.decodeFromString<T>(requireNotNull(response.body).decodeToString())
    }

    private suspend fun <T> retrying(
        operation: String,
        action: suspend () -> T,
    ): T {
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                generateSequence(
                    exception as Throwable,
                ) { it.cause }.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
                val status =
                    generateSequence(
                        exception as Throwable,
                    ) { it.cause }.filterIsInstance<RemoteSignerHttpException>().firstOrNull()?.status
                // Commons 8 treats server errors as terminal too; only 408 and 429 are retried.
                if (status != null && status >= 400 && status != 408 && status != 429) {
                    throw RemoteSignerTerminalException(operation, status, exception)
                }
                logger.warn(exception) { "Failed to $operation. Retrying in 1s" }
                delay(1.seconds)
            }
        }
    }

    override fun close() {
        transport.close()
    }

    @Serializable
    private data class PublicKeyResponse(
        val publicKey: AttoPublicKey,
    )

    @Serializable
    private data class BlockSignatureRequest(
        val target: AttoBlock,
    )

    @Serializable
    private data class VoteSignatureRequest(
        val target: AttoVote,
    )

    @Serializable
    private data class ChallengeSignatureRequest(
        val target: AttoChallenge,
        val timestamp: AttoInstant,
    )

    @Serializable
    private data class SignatureResponse(
        val signature: AttoSignature,
    )

    private class RemoteSignerHttpException(
        val status: Int,
    ) : IllegalStateException("Remote signer returned HTTP $status")
}

class RemoteSignerTerminalException(
    operation: String,
    val status: Int,
    cause: Throwable,
) : IllegalStateException("Remote signer failed to $operation with terminal status $status", cause)

class RemoteSignerInvalidSignatureException(
    targetType: String,
) : IllegalStateException("Remote signer returned invalid $targetType signature")
