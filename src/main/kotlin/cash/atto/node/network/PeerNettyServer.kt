package cash.atto.node.network

import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import reactor.core.publisher.Mono
import reactor.netty.Connection
import reactor.netty.DisposableServer
import reactor.netty.http.HttpProtocol
import reactor.netty.http.server.HttpServer
import reactor.netty.http.server.HttpServerRequest
import reactor.netty.http.server.HttpServerResponse
import reactor.netty.http.server.WebsocketServerSpec
import reactor.netty.resources.LoopResources
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/** Owns the peer listener, application workers and connections independently of Spring HTTP resources. */
internal class PeerNettyServer(
    port: Int,
    private val handshake: suspend (HttpServerRequest, HttpServerResponse) -> Unit,
    private val webSocket: suspend (HttpServerRequest, PeerSession) -> Unit,
) : AutoCloseable {
    private val lifecycle = Any()
    private var accepting = true
    private var closed = false
    private val jobs = mutableSetOf<Job>()
    private val connections = mutableSetOf<Connection>()
    private val loops = LoopResources.create("atto-peer-server-io", 2, true)
    private val dispatcher =
        Executors
            .newThreadPerTaskExecutor(Thread.ofVirtual().name("atto-peer-server-work-", 0).factory())
            .asCoroutineDispatcher()
    private val websocketSpec =
        WebsocketServerSpec
            .builder()
            .maxFramePayloadLength(PeerSession.MAX_MESSAGE_SIZE)
            .compress(false)
            .handlePing(false)
            .build()
    private val server: DisposableServer =
        try {
            HttpServer
                .create()
                .host("0.0.0.0")
                .port(port)
                .runOn(loops)
                .protocol(HttpProtocol.HTTP11)
                .compress(false)
                .doOnConnection { connection ->
                    val accepted =
                        synchronized(lifecycle) {
                            if (!accepting) false else connections.add(connection)
                        }
                    if (accepted) {
                        connection.onDispose().doFinally { synchronized(lifecycle) { connections.remove(connection) } }.subscribe()
                    } else {
                        connection.dispose()
                    }
                }.route { routes ->
                    routes.post("/handshakes") { request, response -> application { handshake(request, response) } }
                    routes.ws(
                        "/",
                        { inbound, outbound ->
                            var connection: Connection? = null
                            inbound.withConnection { connection = it }
                            val session = ReactorPeerSession(requireNotNull(connection), inbound, outbound)
                            application {
                                session.run { peer ->
                                    webSocket(inbound as HttpServerRequest, peer)
                                }
                            }
                        },
                        websocketSpec,
                    )
                }.bindNow(Duration.ofSeconds(5))
        } catch (exception: Throwable) {
            dispatcher.close()
            loops.disposeLater(Duration.ZERO, Duration.ofSeconds(5)).block(Duration.ofSeconds(6))
            throw exception
        }

    private fun application(block: suspend () -> Unit): Mono<Void> =
        mono(dispatcher) {
            val job = currentCoroutineContext()[Job]!!
            synchronized(lifecycle) {
                check(accepting) { "Peer server is stopping" }
                jobs.add(job)
            }
            try {
                block()
            } finally {
                synchronized(lifecycle) { jobs.remove(job) }
            }
        }.then()

    fun stopAccepting() {
        synchronized(lifecycle) { accepting = false }
        server.dispose()
    }

    override fun close() {
        val snapshot =
            synchronized(lifecycle) {
                if (closed) return
                accepting = false
                closed = true
                jobs.toList() to connections.toList()
            }
        server.dispose()
        snapshot.first.forEach { it.cancel() }
        try {
            runBlocking {
                withTimeoutOrNull(5.seconds) { snapshot.first.joinAll() }
                snapshot.second.forEach { it.dispose() }
                withTimeoutOrNull(5.seconds) {
                    snapshot.first.joinAll()
                    snapshot.second.forEach { it.onDispose().awaitSingleOrNull() }
                    server.onDispose().awaitSingleOrNull()
                }
            }
        } finally {
            try {
                loops.disposeLater(Duration.ZERO, Duration.ofSeconds(5)).block(Duration.ofSeconds(6))
            } finally {
                dispatcher.close()
            }
        }
    }
}
