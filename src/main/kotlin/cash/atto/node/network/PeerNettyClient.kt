package cash.atto.node.network

import cash.atto.node.JvmAddressResolverGroup
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.HttpHeaderNames
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import reactor.netty.Connection
import reactor.netty.http.HttpProtocol
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientInfos
import reactor.netty.http.client.HttpClientResponse
import reactor.netty.http.client.WebsocketClientSpec
import reactor.netty.resources.ConnectionProvider
import reactor.netty.resources.LoopResources
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/** Private resources keep peer traffic independent of Spring's HTTP server and signer clients. */
@Component
class PeerNettyClient(
    private val dnsResolver: NetworkDnsResolver,
) : HandshakeCallbackClient {
    private val lifecycle = Any()
    private var closed = false
    private val jobs = mutableSetOf<Job>()
    private val connections = mutableSetOf<Connection>()
    private val sessions = mutableSetOf<ReactorPeerSession>()
    private val dispatcher =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("atto-peer-client-work-", 0).factory()).asCoroutineDispatcher()
    private val loops = LoopResources.create("atto-peer-client-io", 2, true)
    private val websocketConnections = provider("atto-peer-websocket")
    private val callbackConnections = provider("atto-peer-callback")
    private val resolver = JvmAddressResolverGroup(dispatcher)
    private val websocketClient = client(websocketConnections)
    private val callbackClient = client(callbackConnections).keepAlive(false).followRedirect(false)
    private val websocketSpec =
        WebsocketClientSpec
            .builder()
            .maxFramePayloadLength(PeerSession.MAX_MESSAGE_SIZE)
            .compress(false)
            .handlePing(false)
            .build()

    suspend fun webSocket(
        publicUri: URI,
        headers: Map<String, String>,
        block: suspend (PeerSession, InetSocketAddress) -> Unit,
    ) = operation {
        val context = currentCoroutineContext().minusKey(Job)
        websocketClient
            .headers { target -> headers.forEach { (name, value) -> target.set(name, value) } }
            .followRedirect { request, response ->
                val location = response.responseHeaders().get(HttpHeaderNames.LOCATION)
                if (response.status().code() !in REDIRECT_STATUSES || location == null || request.redirectedFrom().size >= 19) {
                    false
                } else {
                    val source = URI(requireNotNull(request.resourceUrl()))
                    val target = source.resolve(location)
                    target.scheme.lowercase() in SUPPORTED_SCHEMES && !(source.isSecure() && !target.isSecure())
                }
            }.websocket(websocketSpec)
            .uri(publicUri)
            .handle { inbound, outbound ->
                var connection: Connection? = null
                inbound.withConnection { connection = it }
                val session = ReactorPeerSession(requireNotNull(connection), inbound, outbound)
                mono(context) {
                    try {
                        operation {
                            synchronized(lifecycle) {
                                check(!closed) { "Peer transport is closed" }
                                sessions.add(session)
                            }
                            session.run { peer ->
                                val effectiveUri = URI(requireNotNull((inbound as HttpClientInfos).resourceUrl()))
                                val port = effectiveUri.port.takeIf { it >= 0 } ?: if (effectiveUri.isSecure()) 443 else 80
                                val address = InetSocketAddress(dnsResolver.getByName(effectiveUri.host), port)
                                block(peer, address)
                            }
                        }
                    } finally {
                        synchronized(lifecycle) { sessions.remove(session) }
                        session.cancel()
                    }
                }
            }.then()
            .awaitSingleOrNull()
        Unit
    }

    override suspend fun postHandshake(
        handshakeUri: URI,
        request: CounterChallengeResponse,
    ): HandshakeCallbackResult.Completed =
        operation {
            withTimeout(NetworkProcessor.CONNECTION_TIMEOUT_IN_SECONDS.seconds) {
                val payload = peerJson.encodeToString(request).encodeToByteArray()
                val connection = AtomicReference<Connection>()
                try {
                    callbackClient
                        .doOnRequest { _, current -> connection.set(current) }
                        .headers {
                            it.set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                            it.setInt(HttpHeaderNames.CONTENT_LENGTH, payload.size)
                        }.post()
                        .uri(handshakeUri)
                        .send(Mono.fromSupplier { Unpooled.wrappedBuffer(payload) })
                        .response { response, body ->
                            receiveHandshakeResponse(response, body)
                        }.single()
                        .awaitSingle()
                } finally {
                    // Cancellation may precede the response handler when a chunk is only partially received.
                    connection.get()?.dispose()
                }
            }
        }

    private fun receiveHandshakeResponse(
        response: HttpClientResponse,
        body: ByteBufFlux,
    ): Mono<HandshakeCallbackResult.Completed> {
        val status = response.status()
        if (status.code() !in 200..299) {
            return Mono.just(HandshakeCallbackResult.Completed(status, null))
        }
        val limit = MAX_CHALLENGE_RESPONSE_SIZE_BYTES
        val declaredSize = response.responseHeaders().get(HttpHeaderNames.CONTENT_LENGTH)?.toLongOrNull()
        return body
            .readHandshakePayload(limit, declaredSize)
            .map { HandshakeCallbackResult.Completed(status, peerJson.decodeFromString<ChallengeResponse>(it)) }
    }

    private fun provider(name: String): ConnectionProvider =
        ConnectionProvider
            .builder(name)
            .maxConnections(1000)
            .pendingAcquireMaxCount(1000)
            .pendingAcquireTimeout(Duration.ofSeconds(NetworkProcessor.CONNECTION_TIMEOUT_IN_SECONDS))
            .build()

    private fun client(provider: ConnectionProvider): HttpClient =
        HttpClient
            .create(provider)
            .runOn(loops)
            .resolver(resolver)
            .protocol(HttpProtocol.HTTP11)
            .compress(false)
            .disableRetry(true)
            .doOnConnected { connection ->
                val accepted =
                    synchronized(lifecycle) {
                        if (closed) false else connections.add(connection)
                    }
                if (accepted) {
                    connection.onDispose().doFinally { synchronized(lifecycle) { connections.remove(connection) } }.subscribe()
                } else {
                    connection.dispose()
                }
            }

    private suspend fun <T> operation(block: suspend CoroutineScope.() -> T): T =
        withContext(dispatcher) {
            coroutineScope {
                val job = currentCoroutineContext()[Job]!!
                synchronized(lifecycle) {
                    check(!closed) { "Peer transport is closed" }
                    jobs.add(job)
                }
                try {
                    block()
                } finally {
                    synchronized(lifecycle) { jobs.remove(job) }
                }
            }
        }

    @PreDestroy
    fun close() {
        val activeJobs: List<Job>
        val activeConnections: List<Connection>
        val activeSessions: List<ReactorPeerSession>
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            activeJobs = jobs.toList()
            activeConnections = connections.toList()
            activeSessions = sessions.toList()
        }
        runBlocking {
            activeSessions.forEach { session ->
                try {
                    session.close()
                } catch (_: CancellationException) {
                    // A full outgoing queue has already aborted this session.
                }
            }
            // Cancelling the outer WebSocket subscription would dispose inbound before the writer can close it.
            withTimeoutOrNull(5.seconds) { activeJobs.joinAll() }
            activeConnections.forEach { it.dispose() }
            activeJobs.forEach { it.cancel() }
            withTimeoutOrNull(5.seconds) {
                activeJobs.joinAll()
                activeConnections.forEach { it.onDispose().awaitSingleOrNull() }
            }
        }
        try {
            Mono.`when`(websocketConnections.disposeLater(), callbackConnections.disposeLater()).block(Duration.ofSeconds(5))
        } finally {
            resolver.close()
            try {
                loops.disposeLater(Duration.ZERO, Duration.ofSeconds(5)).block(Duration.ofSeconds(6))
            } finally {
                dispatcher.close()
            }
        }
    }

    private fun URI.isSecure(): Boolean = scheme.equals("https", true) || scheme.equals("wss", true)

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val SUPPORTED_SCHEMES = setOf("http", "https", "ws", "wss")
    }
}
