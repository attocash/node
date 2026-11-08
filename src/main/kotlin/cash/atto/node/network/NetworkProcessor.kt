package cash.atto.node.network

import cash.atto.commons.AttoChallenge
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoSigner
import cash.atto.commons.fromHexToByteArray
import cash.atto.node.CacheSupport
import cash.atto.node.network.guardian.Guardian
import cash.atto.node.network.guardian.InboundConnectionDecision
import cash.atto.node.transaction.Transaction
import cash.atto.protocol.AttoKeepAlive
import cash.atto.protocol.AttoNode
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerRequest
import reactor.netty.http.server.HttpServerResponse
import java.net.URI
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

private sealed interface InboundHandshakePreparation {
    data class Rejected(
        val status: HttpResponseStatus,
        val closeConnection: Boolean = false,
    ) : InboundHandshakePreparation

    data class Accepted(
        val publicUri: URI,
        val node: AttoNode,
        val response: ChallengeResponse,
    ) : InboundHandshakePreparation
}

private data class InboundPeerHeaders(
    val publicUri: URI,
    val challenge: String,
)

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
    private val peerClient: PeerNettyClient,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val PUBLIC_URI_HEADER = "Atto-Public-Uri"
        const val CHALLENGE_HEADER = "Atto-Http-Challenge"
        const val CONNECTION_TIMEOUT_IN_SECONDS = 5L
        private const val MAX_CONCURRENT_HANDSHAKES = 64
        private const val MAX_CONCURRENT_OUTBOUND_CONNECTIONS = 64
    }

    private val dispatcher =
        Executors
            .newThreadPerTaskExecutor(Thread.ofVirtual().name("atto-peer-connect-work-", 0).factory())
            .asCoroutineDispatcher()
    private val stopped = AtomicBoolean()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val handshakePermits = Semaphore(MAX_CONCURRENT_HANDSHAKES)
    private val outboundConnectionPermits = Semaphore(MAX_CONCURRENT_OUTBOUND_CONNECTIONS)

    private val connectingMap =
        Caffeine
            .newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(Duration.ofSeconds(CONNECTION_TIMEOUT_IN_SECONDS))
            .maximumSize(10_000)
            .build<URI, OutboundConnectionAttempt>()
            .asMap()

    private val server =
        PeerNettyServer(
            environment.getRequiredProperty("websocket.port", Int::class.java),
            ::handleInboundHandshake,
            ::handleInboundWebSocket,
        )

    private suspend fun handleInboundHandshake(
        request: HttpServerRequest,
        response: HttpServerResponse,
    ) {
        if (!handshakePermits.tryAcquire()) {
            response.keepAlive(false).respond(HttpResponseStatus.TOO_MANY_REQUESTS)
            return
        }

        try {
            val preparation =
                withTimeout(CONNECTION_TIMEOUT_IN_SECONDS.seconds) {
                    prepareInboundHandshake(request)
                }
            when (preparation) {
                is InboundHandshakePreparation.Rejected -> {
                    if (preparation.closeConnection) response.keepAlive(false)
                    response.respond(preparation.status)
                }

                is InboundHandshakePreparation.Accepted -> {
                    response.finishInboundHandshake(preparation)
                }
            }
        } catch (e: TimeoutCancellationException) {
            response.keepAlive(false).respond(HttpResponseStatus.REQUEST_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HandshakePayloadTooLargeException) {
            logger.trace(e) { "Oversized handshake from ${request.remoteHost()}" }
            response.keepAlive(false).respond(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE)
        } catch (e: Exception) {
            logger.trace(e) { "Exception during handshake with ${request.remoteHost()}" }
            response.keepAlive(false).respond(HttpResponseStatus.INTERNAL_SERVER_ERROR)
        } finally {
            handshakePermits.release()
        }
    }

    private suspend fun prepareInboundHandshake(request: HttpServerRequest): InboundHandshakePreparation {
        val remoteHost = request.remoteHost()
        val rejection = inboundConnectionRejection(remoteHost, "handshake")
        if (rejection != null) {
            return InboundHandshakePreparation.Rejected(rejection, closeConnection = true)
        }

        val counterResponse =
            request.receive().receiveHandshakePayload<CounterChallengeResponse>(
                MAX_COUNTER_CHALLENGE_RESPONSE_SIZE_BYTES,
                request.requestHeaders().get(HttpHeaderNames.CONTENT_LENGTH)?.toLongOrNull(),
            )
        val challenge = counterResponse.challenge

        val publicUri = ChallengeStore.remove(challenge)
        if (publicUri == null) {
            logger.trace { "Received invalid challenge request from $remoteHost $counterResponse" }
            return InboundHandshakePreparation.Rejected(HttpResponseStatus.BAD_REQUEST)
        }

        val node = counterResponse.node
        if (node.publicUri != publicUri) {
            logger.trace { "Node publicUri ${node.publicUri} doesn't match expected $publicUri from $remoteHost" }
            return InboundHandshakePreparation.Rejected(HttpResponseStatus.BAD_REQUEST)
        }

        if (counterResponse.genesis != genesisTransaction.hash) {
            logger.trace { "Received mismatched genesis hash from $publicUri $remoteHost $counterResponse" }
            return InboundHandshakePreparation.Rejected(HttpResponseStatus.BAD_REQUEST)
        }

        val counterTimestamp = counterResponse.timestamp
        val hash =
            AttoHash.hash(
                64,
                node.publicKey.value,
                challenge.fromHexToByteArray(),
                counterTimestamp.toByteArray(),
            )

        val signature = counterResponse.signature
        if (!signature.isValid(node.publicKey, hash)) {
            logger.trace { "Received invalid signature from server $remoteHost $counterResponse" }
            return InboundHandshakePreparation.Rejected(HttpResponseStatus.BAD_REQUEST)
        }

        val counterChallenge = counterResponse.counterChallenge
        if (!counterChallenge.isChallengePrefixValid()) {
            logger.trace {
                "Received invalid challenge prefix request from $publicUri $remoteHost " +
                    "$counterResponse"
            }
            return InboundHandshakePreparation.Rejected(HttpResponseStatus.BAD_REQUEST)
        }

        logger.trace { "Challenge $challenge validated successfully" }

        val timestamp = AttoInstant.now()
        val response =
            ChallengeResponse(
                thisNode,
                timestamp,
                signer.sign(AttoChallenge(counterChallenge.fromHexToByteArray()), timestamp),
            )

        return InboundHandshakePreparation.Accepted(publicUri, node, response)
    }

    private suspend fun HttpServerResponse.finishInboundHandshake(handshake: InboundHandshakePreparation.Accepted) {
        currentCoroutineContext().ensureActive()
        val connectionAttempt = connectingMap[handshake.publicUri]
        if (connectionAttempt == null || !connectionAttempt.authenticate(handshake.node)) {
            logger.trace { "Received valid handshake but connection already expired" }
            respond(HttpResponseStatus.INTERNAL_SERVER_ERROR)
            return
        }

        status(HttpResponseStatus.OK)
            .header(HttpHeaderNames.CONTENT_TYPE, "application/json")
            .sendString(Mono.just(peerJson.encodeToString(handshake.response)))
            .then()
            .awaitSingleOrNull()
    }

    private suspend fun handleInboundWebSocket(
        request: HttpServerRequest,
        session: PeerSession,
    ) {
        try {
            val remoteHost = request.remoteHost()
            logger.trace { "New websocket connection attempt from $remoteHost" }
            if (inboundConnectionRejection(remoteHost, "websocket") != null) return

            val headers = readInboundPeerHeaders(request, remoteHost) ?: return
            val node = authenticateInboundPeer(remoteHost, headers) ?: return
            connectionManager.manage(node, requireNotNull(request.remoteAddress()), session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.trace(e) { "Exception during handshake with ${request.remoteHost()}" }
        }
    }

    private fun readInboundPeerHeaders(
        request: HttpServerRequest,
        remoteHost: String,
    ): InboundPeerHeaders? {
        val publicUriHeader = request.requestHeaders().get(PUBLIC_URI_HEADER)
        val challengeHeader = request.requestHeaders().get(CHALLENGE_HEADER)
        if (publicUriHeader == null || challengeHeader == null) {
            logger.trace { "Missing required headers from $remoteHost" }
            return null
        }

        val publicUri = publicUriHeader.toUriOrNull()
        if (publicUri == null) {
            logger.trace { "Invalid public URI header '$publicUriHeader' from $remoteHost" }
            return null
        }
        if (publicUri.scheme != "ws" && publicUri.scheme != "wss") {
            logger.trace { "Invalid URI scheme '${publicUri.scheme}' from $remoteHost" }
            return null
        }
        if (publicUri == thisNode.publicUri) {
            logger.trace { "Can't connect as a server to $publicUri. This uri is this node." }
            return null
        }

        logger.trace { "Headers received: publicUri=$publicUri, challenge=$challengeHeader" }
        if (!challengeHeader.isChallengePrefixValid()) {
            logger.trace { "Received invalid challenge prefix request from $publicUri $remoteHost" }
            return null
        }
        return InboundPeerHeaders(publicUri, challengeHeader)
    }

    private suspend fun authenticateInboundPeer(
        remoteHost: String,
        headers: InboundPeerHeaders,
    ): AttoNode? {
        val publicUri = headers.publicUri
        val connectionAttempt = OutboundConnectionAttempt()
        if (connectingMap.putIfAbsent(publicUri, connectionAttempt) != null) {
            logger.trace { "Can't connect as a server to $publicUri. Connection attempt in progress." }
            return null
        }

        var authenticatedNode: AttoNode? = null
        try {
            val timestamp = AttoInstant.now()
            var counterChallenge: String? = null
            val callbackResult =
                handshakeCallbackService.post(remoteHost, publicUri) {
                    val generatedCounterChallenge = ChallengeStore.generate(publicUri)
                    counterChallenge = generatedCounterChallenge
                    CounterChallengeResponse(
                        headers.challenge,
                        genesisTransaction.hash,
                        thisNode,
                        timestamp,
                        signer.sign(AttoChallenge(headers.challenge.fromHexToByteArray()), timestamp),
                        generatedCounterChallenge,
                    )
                }
            val result = callbackResult as? HandshakeCallbackResult.Completed ?: return null
            authenticatedNode = validateInboundCallback(remoteHost, publicUri, counterChallenge, result)
            return authenticatedNode
        } finally {
            // Failed attempts release admission; successful attempts retain the existing expiry window.
            if (authenticatedNode == null) connectingMap.remove(publicUri, connectionAttempt)
        }
    }

    private suspend fun validateInboundCallback(
        remoteHost: String,
        publicUri: URI,
        counterChallenge: String?,
        result: HandshakeCallbackResult.Completed,
    ): AttoNode? {
        logger.trace { "Challenge response status from ${publicUri.toHandshakeHttpUri()}: ${result.status}" }
        if (result.status.code() !in 200..299) {
            logger.trace { "Received invalid ${result.status.code()} challenge status from $publicUri $remoteHost" }
            return null
        }

        val response = result.response
        if (response == null) {
            logger.trace { "Received empty challenge response from $publicUri $remoteHost" }
            return null
        }
        if (counterChallenge == null || ChallengeStore.remove(counterChallenge) == null) {
            logger.trace { "Received invalid challenge response from $publicUri $remoteHost $response" }
            return null
        }

        val node = response.node
        if (node.publicUri != publicUri) {
            logger.trace { "Node publicUri ${node.publicUri} doesn't match header $publicUri from $remoteHost" }
            return null
        }
        val counterHash =
            AttoHash.hash(
                64,
                node.publicKey.value,
                counterChallenge.fromHexToByteArray(),
                response.timestamp.toByteArray(),
            )
        if (!response.signature.isValid(node.publicKey, counterHash)) {
            logger.trace { "Received invalid signature from client $remoteHost $response" }
            return null
        }
        return node
    }

    override fun clear() {
        connectingMap.clear()
    }

    @PreDestroy
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        logger.info { "Network Processor is stopping..." }
        server.stopAccepting()
        clear()
        try {
            runBlocking {
                withTimeoutOrNull(5.seconds) { scope.coroutineContext.job.cancelAndJoin() }
            }
        } finally {
            try {
                closeTransports()
            } finally {
                try {
                    connectionManager.clear()
                } finally {
                    dispatcher.close()
                }
            }
        }
    }

    private fun closeTransports() {
        try {
            server.close()
        } finally {
            peerClient.close()
        }
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
        if (stopped.get() || publicUri == thisNode.publicUri) {
            return
        }

        val requiresPermit = publicUri.toString() !in networkProperties.defaultNodes
        if (requiresPermit && !outboundConnectionPermits.tryAcquire()) {
            return
        }

        val connectionAttempt = OutboundConnectionAttempt()
        if (connectingMap.putIfAbsent(publicUri, connectionAttempt) != null) {
            if (requiresPermit) {
                outboundConnectionPermits.release()
            }
            return
        }

        scope.launch {
            try {
                logger.trace { "Start connection to $publicUri" }
                connection(publicUri, connectionAttempt)
            } catch (e: Exception) {
                logger.trace(e) { "Exception while trying to connect to $publicUri" }
            } finally {
                connectingMap.remove(publicUri, connectionAttempt)
                if (requiresPermit) {
                    outboundConnectionPermits.release()
                }
            }
        }
    }

    private suspend fun inboundConnectionRejection(
        remoteHost: String,
        requestType: String,
    ): HttpResponseStatus? =
        when (guardian.requestInboundConnection(dnsResolver.getByName(remoteHost))) {
            InboundConnectionDecision.Accepted -> {
                null
            }

            InboundConnectionDecision.Banned -> {
                logger.trace { "Rejected $requestType from banned address $remoteHost" }
                HttpResponseStatus.FORBIDDEN
            }

            InboundConnectionDecision.RateLimited -> {
                logger.trace { "Rejected $requestType from rate limited address $remoteHost" }
                HttpResponseStatus.TOO_MANY_REQUESTS
            }
        }

    private suspend fun HttpServerResponse.respond(status: HttpResponseStatus) {
        status(status).send().awaitSingleOrNull()
    }

    private fun HttpServerRequest.remoteHost(): String = requireNotNull(remoteAddress()).address.hostAddress

    private suspend fun connection(
        publicUri: URI,
        connectionAttempt: OutboundConnectionAttempt,
    ) = coroutineScope {
        val connectionAttemptJob = currentCoroutineContext().job
        val timeoutJob =
            launch {
                delay(CONNECTION_TIMEOUT_IN_SECONDS.seconds)
                val timeout = CancellationException("Connection attempt to $publicUri timed out")
                if (connectionAttempt.expire(timeout)) {
                    logger.trace { timeout.message }
                    connectingMap.remove(publicUri, connectionAttempt)
                    connectionAttemptJob.cancel(timeout)
                }
            }

        try {
            val validation = peerUriValidator.validate(publicUri)
            if (validation is PeerUriValidationResult.Rejected) {
                logger.trace { "Can't connect to $publicUri. ${validation.reason}" }
                return@coroutineScope
            }

            if (connectionManager.isConnected(publicUri)) {
                logger.trace { "Can't connect to $publicUri. Connection already established." }
                return@coroutineScope
            }

            logger.trace { "Connecting to $publicUri" }

            peerClient.webSocket(
                publicUri,
                mapOf(
                    PUBLIC_URI_HEADER to thisNode.publicUri.toString(),
                    CHALLENGE_HEADER to ChallengeStore.generate(publicUri),
                ),
            ) { session, connectionSocketAddress ->
                val node = connectionAttempt.awaitNode()

                if (node.publicUri != publicUri) {
                    logger.trace { "Node publicUri ${node.publicUri} doesn't match expected $publicUri" }
                    return@webSocket
                }

                timeoutJob.cancel()
                connectionManager.manage(node, connectionSocketAddress, session)
            }
        } finally {
            timeoutJob.cancel()
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

    private fun String.toUriOrNull(): URI? = runCatching { URI(this) }.getOrNull()
}
