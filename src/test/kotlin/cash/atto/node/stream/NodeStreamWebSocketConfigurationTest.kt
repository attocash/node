package cash.atto.node.stream

import cash.atto.commons.node.AttoStreamCommand
import cash.atto.commons.node.AttoStreamCommandType
import cash.atto.commons.node.AttoStreamMethod
import cash.atto.commons.node.AttoStreamProtocol
import cash.atto.commons.node.AttoStreamRequest
import cash.atto.commons.node.AttoStreamResponse
import cash.atto.commons.node.AttoStreamResponseType
import cash.atto.node.HttpSerializationConfiguration
import cash.atto.node.account.AccountController
import cash.atto.node.account.AccountQueries
import cash.atto.node.account.entry.AccountEntryQueries
import cash.atto.node.receivable.ReceivableQueries
import cash.atto.node.transaction.TransactionQueries
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import reactor.netty.http.server.HttpServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Timeout(15)
class NodeStreamWebSocketConfigurationTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `actual Node configuration serves read streams and rejects WebSocket publication`(publication: Boolean) {
        // Given
        AnnotationConfigApplicationContext(TestConfiguration::class.java).use { context ->
            val adapter = ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build())
            val server =
                HttpServer
                    .create()
                    .host("127.0.0.1")
                    .port(0)
                    .handle(adapter)
                    .bindNow()
            try {
                HttpClient.newHttpClient().use { client ->
                    val responses = CopyOnWriteArrayList<AttoStreamResponse>()
                    val closed = CompletableFuture<Unit>()
                    val listener =
                        object : WebSocket.Listener {
                            private val text = StringBuilder()

                            override fun onOpen(webSocket: WebSocket) {
                                webSocket.request(1)
                            }

                            override fun onText(
                                webSocket: WebSocket,
                                data: CharSequence,
                                last: Boolean,
                            ): CompletionStage<*>? {
                                text.append(data)
                                if (last) {
                                    responses.add(AttoStreamProtocol.decodeResponse(text.toString()))
                                    text.setLength(0)
                                }
                                webSocket.request(1)
                                return null
                            }

                            override fun onClose(
                                webSocket: WebSocket,
                                statusCode: Int,
                                reason: String,
                            ): CompletionStage<*>? {
                                closed.complete(Unit)
                                return null
                            }

                            override fun onError(
                                webSocket: WebSocket,
                                error: Throwable,
                            ) {
                                closed.completeExceptionally(error)
                            }
                        }

                    // When
                    val http =
                        client.send(
                            HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port()}/v2/accounts/stream")).GET().build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    val socket = client.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:${server.port()}/v2/stream"), listener).join()
                    try {
                        socket
                            .sendText(
                                AttoStreamProtocol.encodeCommand(
                                    AttoStreamCommand(
                                        AttoStreamCommandType.SUBSCRIBE,
                                        AttoStreamRequest(
                                            if (publication) AttoStreamMethod.POST else AttoStreamMethod.GET,
                                            if (publication) "/v2/transactions" else "/v2/accounts/stream",
                                        ),
                                    ),
                                ),
                                true,
                            ).join()
                        closed.get(5, TimeUnit.SECONDS)

                        // Then
                        assertEquals(200, http.statusCode())
                        assertEquals("", http.body())
                        val expected =
                            listOf(
                                AttoStreamResponseType.SUBSCRIBED,
                                if (publication) AttoStreamResponseType.ERROR else AttoStreamResponseType.COMPLETE,
                            )
                        assertEquals(expected, responses.map { it.type }.filter { it != AttoStreamResponseType.HEARTBEAT })
                        if (publication) assertEquals(404, responses.last().status)
                    } finally {
                        socket.abort()
                    }
                }
            } finally {
                server.disposeNow()
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    @Import(
        HttpSerializationConfiguration::class,
        NodeStreamWebSocketConfiguration::class,
        NodeStreamDispatcher::class,
        AccountController::class,
    )
    private class TestConfiguration {
        @Bean
        fun accounts(): AccountQueries = mockk<AccountQueries>().also { coEvery { it.stream() } returns emptyFlow() }

        @Bean
        fun entries(): AccountEntryQueries = mockk()

        @Bean
        fun receivables(): ReceivableQueries = mockk()

        @Bean
        fun transactions(): TransactionQueries = mockk()
    }
}
