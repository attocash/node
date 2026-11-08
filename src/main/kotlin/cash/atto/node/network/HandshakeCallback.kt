package cash.atto.node.network

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoInstantAsStringSerializer
import cash.atto.commons.AttoSignature
import cash.atto.protocol.AttoNode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Component
import java.net.URI
import kotlin.time.Duration.Companion.seconds

private val callbackLogger = KotlinLogging.logger {}

@Component
class HandshakeCallbackService(
    private val peerUriValidator: PeerUriValidator,
    private val callbackClient: HandshakeCallbackClient,
) {
    suspend fun post(
        remoteHost: String,
        publicUri: URI,
        requestFactory: suspend () -> CounterChallengeResponse,
    ): HandshakeCallbackResult =
        withTimeout(NetworkProcessor.CONNECTION_TIMEOUT_IN_SECONDS.seconds) {
            val validation = peerUriValidator.validate(publicUri)
            if (validation is PeerUriValidationResult.Rejected) {
                callbackLogger.trace { "Rejected handshake callback to $publicUri from $remoteHost: ${validation.reason}" }
                return@withTimeout HandshakeCallbackResult.Rejected(HttpResponseStatus.BAD_REQUEST)
            }

            callbackClient.postHandshake(publicUri.toHandshakeHttpUri(), requestFactory())
        }
}

interface HandshakeCallbackClient {
    suspend fun postHandshake(
        handshakeUri: URI,
        request: CounterChallengeResponse,
    ): HandshakeCallbackResult.Completed
}

sealed interface HandshakeCallbackResult {
    data class Completed(
        val status: HttpResponseStatus,
        val response: ChallengeResponse?,
    ) : HandshakeCallbackResult

    data class Rejected(
        val status: HttpResponseStatus,
    ) : HandshakeCallbackResult
}

fun URI.toHandshakeHttpUri(): URI {
    val httpScheme =
        when (scheme.lowercase()) {
            "wss" -> "https"
            else -> "http"
        }

    return URI(httpScheme, null, host, port, "/handshakes", null, null)
}

@Serializable
data class ChallengeResponse(
    val node: AttoNode,
    @Serializable(with = AttoInstantAsStringSerializer::class)
    val timestamp: AttoInstant,
    val signature: AttoSignature,
)

@Serializable
data class CounterChallengeResponse(
    val challenge: String,
    val genesis: AttoHash,
    val node: AttoNode,
    @Serializable(with = AttoInstantAsStringSerializer::class)
    val timestamp: AttoInstant,
    val signature: AttoSignature,
    val counterChallenge: String,
)
