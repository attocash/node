package cash.atto.node.network

import io.ktor.websocket.CloseReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.netty.http.client.WebsocketClientSpec
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface PeerWebSocketClient {
    suspend fun connect(
        endpoint: ValidatedPeerEndpoint,
        headers: Map<String, String>,
    ): PeerWebSocketConnection
}

data class PeerWebSocketConnection(
    val remoteAddress: InetSocketAddress,
    val session: PeerWebSocketSession,
)

@Component
class NettyPeerWebSocketClient : PeerWebSocketClient {
    override suspend fun connect(
        endpoint: ValidatedPeerEndpoint,
        headers: Map<String, String>,
    ): PeerWebSocketConnection {
        val pinnedClient = PinnedPeerClient(endpoint)
        val connectedSession = CompletableDeferred<NettyPeerWebSocketSession>()
        val requestHeaders = HttpHeaders()
        headers.forEach(requestHeaders::set)
        val client =
            ReactorNettyWebSocketClient(pinnedClient.httpClient) {
                WebsocketClientSpec
                    .builder()
                    .maxFramePayloadLength(NetworkProcessor.MAX_MESSAGE_SIZE)
            }

        var lifecycle: Disposable? = null
        return try {
            val startedLifecycle =
                client
                    .execute(endpoint.publicUri, requestHeaders) { session ->
                        val peerSession = NettyPeerWebSocketSession(session)
                        connectedSession.complete(peerSession)
                        peerSession.handle()
                    }.subscribe(
                        {},
                        { error -> connectedSession.completeExceptionally(error) },
                        {
                            connectedSession.completeExceptionally(
                                IllegalStateException("Peer WebSocket closed before its session was established"),
                            )
                        },
                    )
            lifecycle = startedLifecycle
            val session = connectedSession.await()
            session.attach(startedLifecycle)
            PeerWebSocketConnection(pinnedClient.connectedAddress(), session)
        } catch (exception: Exception) {
            lifecycle?.dispose()
            throw exception
        } finally {
            pinnedClient.close()
        }
    }
}

private class NettyPeerWebSocketSession(
    private val session: WebSocketSession,
) : PeerWebSocketSession {
    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    private val cancelled = AtomicBoolean()
    private val lifecycle = AtomicReference<Disposable?>()

    override val incoming =
        session
            .receive()
            .handle<ByteArray> { message, sink ->
                if (message.type == WebSocketMessage.Type.BINARY) {
                    val bytes = ByteArray(message.payload.readableByteCount())
                    message.payload.read(bytes)
                    sink.next(bytes)
                }
            }.asFlow()

    fun handle(): Mono<Void> {
        val messages =
            outgoing
                .receiveAsFlow()
                .map { bytes -> session.binaryMessage { factory -> factory.wrap(bytes) } }
                .asFlux()

        return Mono
            .whenDelayError(session.send(messages), session.closeStatus().then())
            .doFinally { outgoing.cancel() }
    }

    fun attach(disposable: Disposable) {
        check(lifecycle.compareAndSet(null, disposable))
        if (cancelled.get()) {
            disposable.dispose()
        }
    }

    override suspend fun send(message: ByteArray) {
        outgoing.send(message)
    }

    override suspend fun close(reason: CloseReason?) {
        val status =
            if (reason == null) {
                CloseStatus.NORMAL
            } else {
                CloseStatus.create(reason.code.toInt(), reason.message)
            }
        try {
            session.close(status).awaitSingleOrNull()
        } finally {
            outgoing.close()
        }
    }

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return
        }
        outgoing.cancel(CancellationException("Peer WebSocket session cancelled"))
        lifecycle.get()?.dispose()
    }
}
