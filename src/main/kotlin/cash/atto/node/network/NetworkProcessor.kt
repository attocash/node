package cash.atto.node.network

import cash.atto.commons.AttoChallenge
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoSigner
import cash.atto.commons.fromHexToByteArray
import cash.atto.commons.isValid
import cash.atto.commons.toByteArray
import cash.atto.node.CacheSupport
import cash.atto.node.network.guardian.Guardian
import cash.atto.node.network.guardian.InboundConnectionDecision
import cash.atto.node.transaction.Transaction
import cash.atto.protocol.AttoKeepAlive
import cash.atto.protocol.AttoNode
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.net.URI
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
    private val peerUriValidator: PeerUriValidator,
    private val handshakeCallbackService: HandshakeCallbackService,
    private val dnsResolver: NetworkDnsResolver,
    private val connectionManager: NodeConnectionManager,
    private val guardian: Guardian,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val MAX_MESSAGE_SIZE = 300
        const val PUBLIC_URI_HEADER = "Atto-Public-Uri"
        const val CHALLENGE_HEADER = "Atto-Http-Challenge"
        const val CONNECTION_TIMEOUT_IN_SECONDS = 5L
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
            .maximumSize(10_000)
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

                        if (!call.acceptInboundConnection(remoteHost, "handshake")) return@post

                        val counterResponse = call.receive<CounterChallengeResponse>()
                        val challenge = counterResponse.challenge

                        val publicUri = ChallengeStore.remove(challenge)
                        if (publicUri == null) {
                            logger.trace { "Received invalid challenge request from $remoteHost $counterResponse" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@post
                        }

                        val node = counterResponse.node

                        if (node.publicUri != publicUri) {
                            logger.trace { "Node publicUri ${node.publicUri} doesn't match expected $publicUri from $remoteHost" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@post
                        }

                        if (counterResponse.genesis != genesisTransaction.hash) {
                            logger.trace { "Received mismatched genesis hash from $publicUri $remoteHost $counterResponse" }
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

                        if (!call.acceptInboundConnection(remoteHost, "websocket")) return@webSocket

                        val publicUriHeader = call.request.headers[PUBLIC_URI_HEADER]
                        val challengeHeader = call.request.headers[CHALLENGE_HEADER]

                        if (publicUriHeader == null || challengeHeader == null) {
                            logger.trace { "Missing required headers from $remoteHost" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val publicUri =
                            runCatching { URI(publicUriHeader) }
                                .getOrElse {
                                    logger.trace { "Invalid public URI header '$publicUriHeader' from $remoteHost" }
                                    call.respond(HttpStatusCode.BadRequest)
                                    return@webSocket
                                }

                        val scheme = publicUri.scheme
                        if (scheme != "ws" && scheme != "wss") {
                            logger.trace { "Invalid URI scheme '$scheme' from $remoteHost" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        if (publicUri == thisNode.publicUri) {
                            logger.trace { "Can't connect as a server to $publicUri. This uri is this node." }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        logger.trace { "Headers received: publicUri=$publicUri, challenge=$challengeHeader" }

                        if (!challengeHeader.isChallengePrefixValid()) {
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

                        val timestamp = AttoInstant.now()
                        var counterChallenge: String? = null
                        val callbackResult =
                            handshakeCallbackService.post(remoteHost, publicUri) {
                                val generatedCounterChallenge = ChallengeStore.generate(publicUri)
                                counterChallenge = generatedCounterChallenge
                                CounterChallengeResponse(
                                    challengeHeader,
                                    genesisTransaction.hash,
                                    thisNode,
                                    timestamp,
                                    signer.sign(AttoChallenge(challengeHeader.fromHexToByteArray()), timestamp),
                                    generatedCounterChallenge,
                                )
                            }

                        if (callbackResult is HandshakeCallbackResult.Rejected) {
                            connectingMap.remove(publicUri)
                            call.respond(callbackResult.status)
                            return@webSocket
                        }

                        val result = callbackResult as HandshakeCallbackResult.Completed

                        logger.trace { "Challenge response status from ${publicUri.toHandshakeHttpUri()}: ${result.status}" }

                        if (!result.status.isSuccess()) {
                            connectingMap.remove(publicUri)
                            logger.trace { "Received invalid ${result.status.value} challenge status from $publicUri $remoteHost" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val response = result.response
                        if (response == null) {
                            connectingMap.remove(publicUri)
                            logger.trace { "Received empty challenge response from $publicUri $remoteHost" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val expectedCounterChallenge = counterChallenge
                        if (expectedCounterChallenge == null || ChallengeStore.remove(expectedCounterChallenge) == null) {
                            connectingMap.remove(publicUri)
                            logger.trace { "Received invalid challenge response from $publicUri $remoteHost $response" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val node = response.node

                        if (node.publicUri != publicUri) {
                            connectingMap.remove(publicUri)
                            logger.trace { "Node publicUri ${node.publicUri} doesn't match header $publicUri from $remoteHost" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val counterHash =
                            AttoHash.hash(
                                64,
                                node.publicKey.value,
                                expectedCounterChallenge.fromHexToByteArray(),
                                response.timestamp.toByteArray(),
                            )

                        val signature = response.signature
                        if (!signature.isValid(node.publicKey, counterHash)) {
                            connectingMap.remove(publicUri)
                            logger.trace { "Received invalid signature from client $remoteHost $response" }
                            call.respond(HttpStatusCode.BadRequest)
                            return@webSocket
                        }

                        val connectionSocketAddress = InetSocketAddress(call.request.origin.remoteHost, call.request.origin.remotePort)

                        connectionManager.manage(node, connectionSocketAddress, this)
                    } catch (_: CancellationException) {
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
        websocketClient.close()
        scope.cancel()
        server.stop()
    }

    @Scheduled(fixedRate = 1_000)
    suspend fun bootstrap() {
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

        val validation = peerUriValidator.validate(publicUri)
        if (validation is PeerUriValidationResult.Rejected) {
            logger.trace { "Can't connect to $publicUri. ${validation.reason}" }
            return
        }

        if (connectionManager.isConnected(publicUri)) {
            return
        }

        scope.launch {
            logger.trace { "Start connection to $publicUri" }
            connection(publicUri)
        }
    }

    private suspend fun ApplicationCall.acceptInboundConnection(
        remoteHost: String,
        requestType: String,
    ): Boolean =
        when (guardian.requestInboundConnection(dnsResolver.getByName(remoteHost))) {
            InboundConnectionDecision.Accepted -> {
                true
            }

            InboundConnectionDecision.Banned -> {
                logger.trace { "Rejected $requestType from banned address $remoteHost" }
                respond(HttpStatusCode.Forbidden)
                false
            }

            InboundConnectionDecision.RateLimited -> {
                logger.trace { "Rejected $requestType from rate limited address $remoteHost" }
                respond(HttpStatusCode.TooManyRequests)
                false
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

            if (node.publicUri != publicUri) {
                logger.trace { "Node publicUri ${node.publicUri} doesn't match expected $publicUri" }
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
}
