package cash.atto.node.network

import cash.atto.commons.AttoChallenge
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoInstantAsStringSerializer
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoSigner
import cash.atto.commons.fromHexToByteArray
import cash.atto.commons.isValid
import cash.atto.commons.toByteArray
import cash.atto.node.CacheSupport
import cash.atto.node.transaction.Transaction
import cash.atto.protocol.AttoKeepAlive
import cash.atto.protocol.AttoNode
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@Component
class NetworkProcessor(
    private val genesisTransaction: Transaction,
    private val thisNode: AttoNode,
    private val signer: AttoSigner,
    environment: Environment,
    private val networkProperties: NetworkProperties,
    private val connectionManager: NodeConnectionManager,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val MAX_MESSAGE_SIZE = 300
        const val PUBLIC_URI_HEADER = "Atto-Public-Uri"
        const val CHALLENGE_HEADER = "Atto-Http-Challenge"
        const val CONNECTION_TIMEOUT_IN_SECONDS = 5L
    }

    val random = SecureRandom.getInstanceStrong()!!

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
                requestTimeoutMillis = CONNECTION_TIMEOUT_IN_SECONDS.seconds.inWholeMilliseconds
            }
        }

    private val websocketClient =
        HttpClient(io.ktor.client.engine.cio.CIO) {
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

    private val scope = CoroutineScope(Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher() + SupervisorJob())

    private val connectingMap =
        Caffeine
            .newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(Duration.ofSeconds(CONNECTION_TIMEOUT_IN_SECONDS))
            .build<URI, MutableSharedFlow<AttoNode>>()
            .asMap()

    private val server =
        embeddedServer(io.ktor.server.cio.CIO, port = environment.getRequiredProperty("websocket.port", Int::class.java)) {
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

                        val counterTimestamp = counterResponse.timestamp
                        val hash = AttoHash.hash(64, node.publicKey.value, challenge.fromHexToByteArray(), counterTimestamp.toByteArray())

                        val signature = counterResponse.signature
                        if (!signature.isValid(node.publicKey, hash)) {
                            logger.trace { "Received invalid signature from server $remoteHost $counterResponse" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@post
                        }

                        val counterChallenge = counterResponse.counterChallenge
                        if (!counterChallenge.isChallengePrefixValid()) {
                            logger.trace { "Received invalid challenge prefix request from $publicUri $remoteHost $counterResponse" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@post
                        }

                        logger.trace { "Challenge $challenge validated successfully" }

                        val timestamp = AttoInstant.now()
                        val response =
                            ChallengeResponse(
                                thisNode,
                                timestamp,
                                signer.sign(AttoChallenge(counterChallenge.fromHexToByteArray()), timestamp),
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

                        if (publicUri == thisNode.publicUri) {
                            logger.trace { "Can't connect as a server to $publicUri. This uri is this node." }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val challenge = call.request.headers[CHALLENGE_HEADER]!!

                        logger.trace { "Headers received: publicUri=$publicUri, challenge=$challenge" }

                        if (!challenge.isChallengePrefixValid()) {
                            call.respond(HttpStatusCode.BadRequest)
                            logger.trace { "Received invalid challenge prefix request from $publicUri $remoteHost" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val connectingFlow = MutableSharedFlow<AttoNode>(1)

                        if (connectingMap.putIfAbsent(publicUri, connectingFlow) != null) {
                            logger.trace { "Can't connect as a server to $publicUri. Connection attempt in progress." }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val httpUri =
                            publicUri
                                .toString()
                                .replaceFirst("wss://", "https://")
                                .replaceFirst("ws://", "http://")

                        val timestamp = AttoInstant.now()
                        val counterChallenge = ChallengeStore.generate(publicUri)
                        val counterResponse =
                            CounterChallengeResponse(
                                challenge,
                                genesisTransaction.hash,
                                thisNode,
                                timestamp,
                                signer.sign(AttoChallenge(challenge.fromHexToByteArray()), timestamp),
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
                            connectingMap.remove(publicUri)
                            logger.trace { "Received invalid ${result.status.value} challenge status from $publicUri $remoteHost" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val response = result.body<ChallengeResponse>()

                        if (!ChallengeStore.remove(publicUri, counterChallenge)) {
                            connectingMap.remove(publicUri)
                            logger.trace { "Received invalid challenge response from $publicUri $remoteHost $response" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val node = response.node

                        val counterHash =
                            AttoHash.hash(64, node.publicKey.value, counterChallenge.fromHexToByteArray(), response.timestamp.toByteArray())

                        val signature = response.signature
                        if (!signature.isValid(node.publicKey, counterHash)) {
                            connectingMap.remove(publicUri)
                            logger.trace { "Received invalid signature from client $remoteHost $response" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val connectionSocketAddress = InetSocketAddress(call.request.origin.remoteHost, call.request.origin.remotePort)

                        connectionManager.manage(node, connectionSocketAddress, this)
                    } catch (e: Exception) {
                        logger.trace(e) { "Exception during handshake with ${call.request.origin.remoteHost}" }
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                }
            }
        }.start(wait = false)

    override fun clear() {
        connectingMap.clear()
    }

    @PreDestroy
    fun stop() {
        logger.info { "Network Processor is stopping..." }
        clear()
        httpClient.close()
        websocketClient.close()
        server.stop()
        scope.cancel()
    }

    @Scheduled(fixedRate = 1_000)
    suspend fun boostrap() {
        networkProperties
            .defaultNodes
            .asSequence()
            .map { URI(it) }
            .forEach {
                connectAsync(it)
            }
    }

    @EventListener
    suspend fun onKeepAlive(message: InboundNetworkMessage<AttoKeepAlive>) {
        val keepAlive = message.payload
        val neighbour = keepAlive.neighbour ?: return

        connectAsync(neighbour)
    }

    private suspend fun connectAsync(publicUri: URI) {
        if (publicUri == thisNode.publicUri) {
            return
        }

        if (connectionManager.isConnected(publicUri)) {
            return
        }

        scope.launch {
            try {
                logger.trace { "Start connection to $publicUri" }
                connection(publicUri)
            } catch (e: Exception) {
                logger.trace(e) { "Exception during connection to $publicUri" }
            }
        }
    }

    private suspend fun connection(publicUri: URI) {
        if (publicUri == thisNode.publicUri) {
            logger.trace { "Can't connect to $publicUri. This uri is this node." }
            return
        }

        val connectingFlow = MutableSharedFlow<AttoNode>(1)

        if (connectingMap.putIfAbsent(publicUri, connectingFlow) != null) {
            logger.trace { "Can't connect to $publicUri. Connection attempt in progress." }
            return
        }

        if (connectionManager.isConnected(publicUri)) {
            connectingMap.remove(publicUri)
            logger.trace { "Can't connect to $publicUri. Connection already established." }
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
                logger.trace { "Handshake with $publicUri timed out" }
                return
            }

            connectionManager.manage(node, connectionSocketAddress, session)
        } catch (e: Exception) {
            logger.trace(e) { "Exception while trying to connect to $publicUri" }
        } finally {
            connectingMap.remove(publicUri)
        }
    }

    private fun String.isChallengePrefixValid(): Boolean {
        val challenge = this.fromHexToByteArray()

        if (challenge.size <= ChallengeStore.CHALLENGE_SIZE) {
            return false
        }

        val prefixEnd = challenge.size - ChallengeStore.CHALLENGE_SIZE
        val url = challenge.sliceArray(0 until prefixEnd).toString(Charsets.UTF_8)

        return url == thisNode.publicUri.toString()
    }

    @Serializable
    private data class ChallengeResponse(
        val node: AttoNode,
        @Serializable(with = AttoInstantAsStringSerializer::class)
        val timestamp: AttoInstant,
        val signature: AttoSignature,
    )

    @Serializable
    private data class CounterChallengeResponse(
        val challenge: String,
        val genesis: AttoHash,
        val node: AttoNode,
        @Serializable(with = AttoInstantAsStringSerializer::class)
        val timestamp: AttoInstant,
        val signature: AttoSignature,
        val counterChallenge: String,
    )
}
