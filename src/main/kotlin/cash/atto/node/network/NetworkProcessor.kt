package cash.atto.node.network

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.fromHexToByteArray
import cash.atto.commons.sign
import cash.atto.commons.toByteArray
import cash.atto.node.attoCoroutineExceptionHandler
import cash.atto.node.transaction.Transaction
import cash.atto.protocol.AttoKeepAlive
import cash.atto.protocol.AttoNode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

@Component
class NetworkProcessor(
    private val genesisTransaction: Transaction,
    private val thisNode: AttoNode,
    private val privateKey: AttoPrivateKey,
    environment: Environment,
    private val networkProperties: NetworkProperties,
    private val connectionManager: NodeConnectionManager,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val MAX_MESSAGE_SIZE = 272
        const val PUBLIC_URI_HEADER = "Atto-Public-Uri"
        const val CHALLENGE_HEADER = "Atto-Http-Challenge"
    }

    val random = SecureRandom.getInstanceStrong()!!

    private val httpClient =
        HttpClient(CIO) {
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
        }

    private val websocketClient =
        HttpClient(CIO) {
            install(
                io
                    .ktor
                    .client
                    .plugins
                    .websocket
                    .WebSockets,
            ) {
                maxFrameSize = MAX_MESSAGE_SIZE.toLong()
            }
        }

    private val defaultScope = CoroutineScope(Dispatchers.Default + attoCoroutineExceptionHandler)

    private val connectingMap = ConcurrentHashMap<URI, MutableSharedFlow<AttoNode>>()

    private val server =
        embeddedServer(Netty, port = environment.getRequiredProperty("websocket.port", Int::class.java)) {
            install(
                io
                    .ktor
                    .server
                    .websocket
                    .WebSockets,
            ) {
                maxFrameSize = MAX_MESSAGE_SIZE.toLong()
            }
            install(
                io
                    .ktor
                    .server
                    .plugins
                    .contentnegotiation
                    .ContentNegotiation,
            ) {
                json()
            }
            routing {
                post("/handshakes") {
                    val remoteHost = call.request.origin.remoteHost
                    val counterResponse = call.receive<CounterChallengeResponse>()
                    val node = counterResponse.node
                    val publicUri = node.publicUri
                    val challenge = counterResponse.challenge

                    if (!ChallengeStore.remove(publicUri, challenge)) {
                        logger.trace { "Received invalid challenge request from $publicUri $remoteHost $counterResponse" }
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }

                    val nonce = counterResponse.nonce
                    val hash = AttoHash.hash(64, challenge.fromHexToByteArray(), nonce.toByteArray())

                    val signature = counterResponse.signature
                    if (!signature.isValid(node.publicKey, hash)) {
                        logger.trace { "Received invalid signature from server $remoteHost $counterResponse" }
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }

                    logger.trace { "Challenge $challenge validated successfully" }

                    val counterHash = AttoHash.hash(64, counterResponse.counterChallenge.fromHexToByteArray(), nonce.toByteArray())

                    val response =
                        ChallengeResponse(
                            thisNode,
                            privateKey.sign(counterHash),
                        )

                    val connectingFlow = connectingMap[publicUri]

                    if (connectingFlow == null) {
                        logger.trace { "Received valid handshake but connection already expired" }
                        call.respond(HttpStatusCode.InternalServerError)
                        return@post
                    }

                    connectingFlow.emit(node)

                    call.respond(HttpStatusCode.OK, response)
                }
                webSocket(path = "/") {
                    try {
                        val remoteHost = call.request.origin.remoteHost
                        val port = call.request.origin.localPort

                        logger.info { "New websocket connection attempt from $remoteHost" }

                        val publicUri = URI(call.request.headers[PUBLIC_URI_HEADER]!!)
                        val challenge = call.request.headers[CHALLENGE_HEADER]!!

                        logger.trace { "Headers received: publicUri=$publicUri, challenge=$challenge" }

                        val httpUri =
                            publicUri
                                .toString()
                                .replaceFirst("wss://", "https://")
                                .replaceFirst("ws://", "http://")

                        val nonce = random.nextLong().toULong()
                        val hash = AttoHash.hash(64, challenge.fromHexToByteArray(), nonce.toByteArray())
                        val counterChallenge = ChallengeStore.generate(publicUri)
                        val counterResponse =
                            CounterChallengeResponse(
                                challenge,
                                genesisTransaction.hash,
                                thisNode,
                                nonce,
                                privateKey.sign(hash),
                                counterChallenge,
                            )
                        val result =
                            httpClient.post("$httpUri/handshakes") {
                                contentType(
                                    io
                                        .ktor
                                        .http
                                        .ContentType
                                        .Application
                                        .Json,
                                )
                                setBody(counterResponse)
                            }

                        logger.trace { "Challenge response status from $httpUri: ${result.status}" }

                        if (!result.status.isSuccess()) {
                            outgoing.close()
                            return@webSocket
                        }

                        val response = result.body<ChallengeResponse>()

                        if (!ChallengeStore.remove(publicUri, counterChallenge)) {
                            logger.trace { "Received invalid challenge request from $publicUri $remoteHost $response" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val counterHash = AttoHash.hash(64, counterChallenge.fromHexToByteArray(), nonce.toByteArray())

                        val signature = response.signature
                        if (!signature.isValid(response.node.publicKey, counterHash)) {
                            logger.trace { "Received invalid signature from client $remoteHost $response" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val connectionSocketAddress = InetSocketAddress(call.request.host(), call.request.port())

                        connectionManager.manage(response.node, connectionSocketAddress, this)
                    } catch (e: Exception) {
                        logger.trace(e) { "Exception during handshake with ${call.request.origin.remoteHost}" }
                        close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Exception during handshake"))
                    }
                }
            }
        }.start(wait = false)

    @PostConstruct
    fun start() {
        runBlocking {
            boostrap()
        }
    }

    @PreDestroy
    fun stop() {
        server.stop()
        defaultScope.cancel()
    }

    @Scheduled(fixedRate = 60_000)
    suspend fun boostrap() {
        withContext(Dispatchers.Default) {
            networkProperties
                .defaultNodes
                .asSequence()
                .map { URI(it) }
                .forEach {
                    connection(it)
                }
        }
    }

    @EventListener
    suspend fun onKeepAlive(event: AttoKeepAlive) {
        val neighbour = event.neighbour ?: return
        connection(neighbour)
    }

    private suspend fun connection(publicUri: URI) {
        if (connectionManager.isConnected(publicUri)) {
            return
        }

        logger.trace { "Connecting to $publicUri" }

        val connectingFlow = MutableSharedFlow<AttoNode>(1)

        connectingMap[publicUri] = connectingFlow

        val session =
            websocketClient.webSocketSession(publicUri.toString()) {
                header(PUBLIC_URI_HEADER, thisNode.publicUri.toString())
                header(CHALLENGE_HEADER, ChallengeStore.generate(publicUri))
            }

        val connectionSocketAddress =
            InetSocketAddress(
                session
                    .call
                    .request
                    .url
                    .host,
                session
                    .call
                    .request
                    .url
                    .port,
            )

        val node =
            withTimeoutOrNull(5_000) {
                connectingFlow
                    .onCompletion { connectingMap.remove(publicUri) }
                    .first()
            }

        if (node == null) {
            logger.trace { "Handshake timed out" }
            return
        }

        defaultScope.launch {
            connectionManager.manage(node, connectionSocketAddress, session)
        }
    }
}

@Serializable
private data class ChallengeResponse(
    val node: AttoNode,
    val signature: AttoSignature,
)

@Serializable
private data class CounterChallengeResponse(
    val challenge: String,
    val genesis: AttoHash,
    val node: AttoNode,
    val nonce: ULong,
    val signature: AttoSignature,
    val counterChallenge: String,
)

private enum class NodeConnectionStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}
