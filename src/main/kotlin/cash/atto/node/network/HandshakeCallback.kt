package cash.atto.node.network

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoInstantAsStringSerializer
import cash.atto.commons.AttoSignature
import cash.atto.protocol.AttoNode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration

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
        return when (validation) {
            is PeerUriValidationResult.Rejected -> {
                callbackLogger.trace { "Rejected handshake callback to $publicUri from $remoteHost: ${validation.reason}" }
                HandshakeCallbackResult.Rejected(HttpStatusCode.BadRequest)
            }

            is PeerUriValidationResult.Accepted -> {
                callbackClient.post(validation.endpoint, requestFactory())
            }
        }
    }
}

interface HandshakeCallbackClient {
    suspend fun post(
        endpoint: ValidatedPeerEndpoint,
        request: CounterChallengeResponse,
    ): HandshakeCallbackResult.Completed
}

@Component
class NettyHandshakeCallbackClient : HandshakeCallbackClient {
    override suspend fun post(
        endpoint: ValidatedPeerEndpoint,
        request: CounterChallengeResponse,
    ): HandshakeCallbackResult.Completed {
        val requestBody = Json.encodeToString(request)
        val pinnedClient = PinnedPeerClient(endpoint)

        return try {
            pinnedClient.httpClient
                .headers { headers ->
                    headers.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                }.post()
                .uri(endpoint.publicUri.toHandshakeHttpUri().toString())
                .send(ByteBufFlux.fromString(Mono.just(requestBody)))
                .responseSingle { response, body ->
                    val status = HttpStatusCode.fromValue(response.status().code())
                    if (status.isSuccess()) {
                        body.asString(StandardCharsets.UTF_8).map { responseBody ->
                            HandshakeCallbackResult.Completed(
                                status = status,
                                response = Json.decodeFromString<ChallengeResponse>(responseBody),
                            )
                        }
                    } else {
                        body.thenReturn(HandshakeCallbackResult.Completed(status = status, response = null))
                    }
                }.timeout(Duration.ofSeconds(NetworkProcessor.CONNECTION_TIMEOUT_IN_SECONDS))
                .awaitSingle()
        } finally {
            pinnedClient.close()
        }
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
