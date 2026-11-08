package cash.atto.node.signature

import cash.atto.node.JvmAddressResolverGroup
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelOption
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import reactor.core.publisher.Mono
import reactor.netty.Connection
import reactor.netty.http.HttpProtocol
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import reactor.netty.resources.LoopResources
import java.net.URI
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

internal class RemoteSignerHttpTransport(
    url: String,
    token: String,
) : AutoCloseable {
    private val baseUrl = url.trimEnd('/')
    private val dispatcher =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("atto-signer-work-", 0).factory()).asCoroutineDispatcher()
    private val loops = LoopResources.create("atto-signer-io", 2, true)
    private val provider =
        ConnectionProvider
            .builder(
                "atto-signer",
            ).maxConnections(100)
            .pendingAcquireMaxCount(1000)
            .maxIdleTime(Duration.ofSeconds(5))
            .build()
    private val lifecycle = Any()
    private var closed = false
    private val jobs = mutableSetOf<Job>()
    private val connections = mutableSetOf<Connection>()
    private val resolver = JvmAddressResolverGroup(dispatcher)
    private val client =
        HttpClient
            .create(provider)
            .runOn(loops)
            .resolver(resolver)
            .protocol(HttpProtocol.HTTP11)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .disableRetry(true)
            .compress(false)
            .headers {
                it.set(HttpHeaderNames.AUTHORIZATION, token)
                it.set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                it.set(HttpHeaderNames.ACCEPT, "application/json")
            }.followRedirect { request, response ->
                val location = response.responseHeaders().get(HttpHeaderNames.LOCATION)
                if (request.method() != HttpMethod.GET ||
                    response.status().code() !in REDIRECT_STATUSES ||
                    location == null ||
                    request.redirectedFrom().size >= 19
                ) {
                    false
                } else {
                    val source = URI(requireNotNull(request.resourceUrl()))
                    val target = source.resolve(location)
                    target.scheme in setOf("http", "https") && !(source.scheme == "https" && target.scheme != "https")
                }
            }.doOnConnected { connection ->
                val accepted = synchronized(lifecycle) { if (closed) false else connections.add(connection) }
                if (accepted) {
                    connection.onDispose().doFinally { synchronized(lifecycle) { connections.remove(connection) } }.subscribe()
                } else {
                    connection.dispose()
                }
            }

    /** Covers retries, codecs and signature validation as well as their HTTP requests. */
    suspend fun <T> operation(action: suspend () -> T): T =
        coroutineScope {
            val job = currentCoroutineContext()[Job]!!
            synchronized(lifecycle) {
                check(!closed) { "Remote signer is closed" }
                jobs.add(job)
            }
            try {
                withContext(dispatcher) { action() }
            } finally {
                synchronized(lifecycle) { jobs.remove(job) }
            }
        }

    suspend fun request(
        path: String,
        payload: ByteArray?,
        timeoutSeconds: Long,
    ): Response {
        val configured = client.responseTimeout(Duration.ofSeconds(timeoutSeconds))
        val request =
            if (payload == null) {
                configured.get().uri("$baseUrl/$path")
            } else {
                configured.post().uri("$baseUrl/$path").send(Mono.fromSupplier { Unpooled.wrappedBuffer(payload) })
            }
        return request
            .response { response, content ->
                val status = response.status().code()
                if (status >= 300) {
                    // Return the status immediately; an error body may never finish.
                    Mono.just(Response(status, null))
                } else {
                    content.aggregate().asByteArray().map { Response(status, it) }
                }
            }.single()
            .awaitSingle()
    }

    override fun close() {
        val snapshot =
            synchronized(lifecycle) {
                if (closed) return
                closed = true
                jobs.toList() to connections.toList()
            }
        snapshot.first.forEach { it.cancel() }
        snapshot.second.forEach { it.dispose() }
        try {
            runBlocking {
                withTimeoutOrNull(5.seconds) {
                    snapshot.first.joinAll()
                    snapshot.second.forEach { it.onDispose().awaitSingleOrNull() }
                }
            }
            provider.disposeLater().block(Duration.ofSeconds(5))
        } finally {
            resolver.close()
            try {
                loops.disposeLater(Duration.ZERO, Duration.ofSeconds(5)).block(Duration.ofSeconds(6))
            } finally {
                dispatcher.close()
            }
        }
    }

    class Response(
        val status: Int,
        val body: ByteArray?,
    )

    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}
