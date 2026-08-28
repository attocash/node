package cash.atto.node

import cash.atto.protocol.AttoNode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.net.InetSocketAddress

class GlobalRequestInterceptorTest {
    @Test
    fun `rejects api request using the local port when host omits it`() {
        // Given
        val thisNode = nonHistoricalVoter()
        val interceptor = GlobalRequestInterceptor(thisNode, NodeProperties())
        val chain = mockk<WebFilterChain>()
        val exchange = exchange(8080)

        // When
        val result = interceptor.filter(exchange, chain)

        // Then
        StepVerifier
            .create(result)
            .expectErrorSatisfies { exception ->
                assertInstanceOf(ResponseStatusException::class.java, exception)
                assertEquals(HttpStatus.FORBIDDEN, (exception as ResponseStatusException).statusCode)
            }.verify()
        verify(exactly = 0) { chain.filter(any()) }
    }

    @Test
    fun `allows management request using its local port`() {
        // Given
        val thisNode = nonHistoricalVoter()
        val interceptor = GlobalRequestInterceptor(thisNode, NodeProperties())
        val chain = mockk<WebFilterChain>()
        val exchange = exchange(8081)
        every { chain.filter(exchange) } returns Mono.empty()

        // When
        val result = interceptor.filter(exchange, chain)

        // Then
        StepVerifier.create(result).verifyComplete()
        verify(exactly = 1) { chain.filter(exchange) }
    }

    private fun nonHistoricalVoter(): AttoNode =
        mockk {
            every { isVoter() } returns true
            every { isNotHistorical() } returns true
        }

    private fun exchange(port: Int): MockServerWebExchange =
        MockServerWebExchange.from(
            MockServerHttpRequest
                .get("http://node.test/")
                .localAddress(InetSocketAddress("127.0.0.1", port))
                .build(),
        )
}
