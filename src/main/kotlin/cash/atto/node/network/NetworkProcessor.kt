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
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
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
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
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
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

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
        const val CONNECTION_TIMEOUT_IN_SECONDS = 5L
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

            install(HttpTimeout) {
                requestTimeoutMillis = CONNECTION_TIMEOUT_IN_SECONDS.seconds.inWholeMilliseconds
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

    private val connectingMap =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofSeconds(CONNECTION_TIMEOUT_IN_SECONDS))
            .build<URI, MutableSharedFlow<AttoNode>>()
            .asMap()

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
                    try {
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
                    } catch (e: Exception) {
                        logger.trace(e) { "Exception during handshake with ${call.request.origin.remoteHost}" }
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                }
                webSocket(path = "/") {
                    try {
                        val remoteHost = call.request.origin.remoteHost

                        logger.trace { "New websocket connection attempt from $remoteHost" }

                        val publicUri = URI(call.request.headers[PUBLIC_URI_HEADER]!!)
                        val challenge = call.request.headers[CHALLENGE_HEADER]!!

                        logger.trace { "Headers received: publicUri=$publicUri, challenge=$challenge" }

                        val connectingFlow = MutableSharedFlow<AttoNode>(1)

                        val existingFlow = connectingMap.putIfAbsent(publicUri, connectingFlow)
                        if (existingFlow != null) {
                            logger.trace { "Can't connect as a server to $publicUri. Connection attempt in progress." }
                            return@webSocket
                        }

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
                            return@webSocket
                        }

                        val response = result.body<ChallengeResponse>()

                        if (!ChallengeStore.remove(publicUri, counterChallenge)) {
                            logger.trace { "Received invalid challenge request from $publicUri $remoteHost $response" }
                            return@webSocket
                        }

                        val counterHash = AttoHash.hash(64, counterChallenge.fromHexToByteArray(), nonce.toByteArray())

                        val signature = response.signature
                        if (!signature.isValid(response.node.publicKey, counterHash)) {
                            logger.trace { "Received invalid signature from client $remoteHost $response" }
                            return@webSocket
                        }

                        val connectionSocketAddress = InetSocketAddress(call.request.host(), call.request.port())

                        connectionManager.manage(response.node, connectionSocketAddress, this)
                    } catch (e: Exception) {
                        logger.trace(e) { "Exception during handshake with ${call.request.origin.remoteHost}" }
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
                    connectAsync(it)
                }
        }
    }

    @EventListener
    suspend fun onKeepAlive(message: InboundNetworkMessage<AttoKeepAlive>) {
        val keepAlive = message.payload
        val neighbour = keepAlive.neighbour ?: return
        connectAsync(neighbour)
    }

    private suspend fun connectAsync(publicUri: URI) {
        defaultScope.launch {
            logger.trace { "Start connection to $publicUri" }
            connection(publicUri)
        }
    }

    private suspend fun connection(publicUri: URI) {
        if (publicUri == thisNode.publicUri) {
            logger.trace { "Can't connect to $publicUri. This uri is this node." }
            return
        }

        if (connectionManager.isConnected(publicUri)) {
            logger.trace { "Can't connect to $publicUri. Connection already established." }
            return
        }

        if (connectingMap.containsKey(publicUri)) {
            logger.trace { "Can't connect as a client to $publicUri. Connection attempt in progress." }
            return
        }

        val connectingFlow = MutableSharedFlow<AttoNode>(1)

        val existingFlow = connectingMap.putIfAbsent(publicUri, connectingFlow)
        if (existingFlow != null) {
            logger.trace { "Can't connect to $publicUri. Connection attempt in progress." }
            return
        }

        logger.trace { "Connecting to $publicUri" }

        try {
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
                withTimeoutOrNull(CONNECTION_TIMEOUT_IN_SECONDS.seconds) {
                    connectingFlow.first()
                }

            if (node == null) {
                logger.trace { "Handshake timed out" }
                return
            }

            connectionManager.manage(node, connectionSocketAddress, session)
        } catch (e: Exception) {
            logger.trace(e) { "Exception while trying to connect to $publicUri" }
        } finally {
            connectingMap.remove(publicUri)
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
