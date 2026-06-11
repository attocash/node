package cash.atto.node.network

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoInstantAsStringSerializer
import cash.atto.commons.AttoSignature
import cash.atto.protocol.AttoNode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import jakarta.annotation.PreDestroy
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
    ): HandshakeCallbackResult {
        val validation = peerUriValidator.validate(publicUri)
        if (validation is PeerUriValidationResult.Rejected) {
            callbackLogger.trace { "Rejected handshake callback to $publicUri from $remoteHost: ${validation.reason}" }
            return HandshakeCallbackResult.Rejected(HttpStatusCode.BadRequest)
        }

        return callbackClient.post(publicUri.toHandshakeHttpUri(), requestFactory())
    }
}

interface HandshakeCallbackClient {
    suspend fun post(
        handshakeUri: URI,
        request: CounterChallengeResponse,
    ): HandshakeCallbackResult.Completed
}

@Component
class KtorHandshakeCallbackClient : HandshakeCallbackClient {
    private val httpClient =
        HttpClient(io.ktor.client.engine.cio.CIO) {
            install(
                io
                    .ktor
                    .client
                    .plugins
                    .contentnegotiation
                    .ContentNegotiation,
            ) {
                json()
            }

            install(HttpTimeout) {
                requestTimeoutMillis = NetworkProcessor.CONNECTION_TIMEOUT_IN_SECONDS.seconds.inWholeMilliseconds
            }
        }

    override suspend fun post(
        handshakeUri: URI,
        request: CounterChallengeResponse,
    ): HandshakeCallbackResult.Completed {
        val response =
            httpClient.post(handshakeUri.toString()) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

        return HandshakeCallbackResult.Completed(
            status = response.status,
            response = if (response.status.value in 200..299) response.body<ChallengeResponse>() else null,
        )
    }

    @PreDestroy
    fun close() {
        httpClient.close()
    }
}

sealed interface HandshakeCallbackResult {
    data class Completed(
        val status: HttpStatusCode,
        val response: ChallengeResponse?,
    ) : HandshakeCallbackResult

    data class Rejected(
        val status: HttpStatusCode,
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
