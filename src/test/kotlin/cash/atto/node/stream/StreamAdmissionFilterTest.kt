package cash.atto.node.stream

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.node.GlobalRequestInterceptor
import cash.atto.node.NodeProperties
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.net.URI

internal class StreamAdmissionFilterTest {
    @Test
    fun `an active request beyond the configured limit is rejected`() {
        // given
        val meterRegistry = SimpleMeterRegistry()
        val filter = filter(maxActiveStreams = 2, meterRegistry)
        val neverCompletes = WebFilterChain { Mono.never() }
        val first = filter.filter(exchange(), neverCompletes).subscribe()
        val second = filter.filter(exchange(), neverCompletes).subscribe()
        val rejectedExchange = exchange()

        // when
        StepVerifier.create(filter.filter(rejectedExchange, neverCompletes)).verifyComplete()

        // then
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejectedExchange.response.statusCode)
        assertEquals(2.0, meterRegistry.activeStreams())
        assertEquals(1.0, meterRegistry.rejectedAdmissions())
        first.dispose()
        second.dispose()
        assertEquals(0.0, meterRegistry.activeStreams())
    }

    @Test
    fun `a permit is reusable after normal completion`() {
        // given
        val meterRegistry = SimpleMeterRegistry()
        val filter = filter(maxActiveStreams = 1, meterRegistry)
        val completes = WebFilterChain { Mono.empty() }

        // when
        StepVerifier.create(filter.filter(exchange(), completes)).verifyComplete()
        StepVerifier.create(filter.filter(exchange(), completes)).verifyComplete()

        // then
        assertEquals(0.0, meterRegistry.activeStreams())
        assertEquals(0.0, meterRegistry.rejectedAdmissions())
    }

    @Test
    fun `a permit is reusable after handler error`() {
        // given
        val meterRegistry = SimpleMeterRegistry()
        val filter = filter(maxActiveStreams = 1, meterRegistry)
        val failure = IllegalStateException("handler failed")
        val fails = WebFilterChain { Mono.error(failure) }

        // when
        StepVerifier.create(filter.filter(exchange(), fails)).expectErrorMatches { it === failure }.verify()
        StepVerifier.create(filter.filter(exchange(), WebFilterChain { Mono.empty() })).verifyComplete()

        // then
        assertEquals(0.0, meterRegistry.activeStreams())
        assertEquals(0.0, meterRegistry.rejectedAdmissions())
    }

    @Test
    fun `a permit is reusable after client cancellation`() {
        // given
        val meterRegistry = SimpleMeterRegistry()
        val filter = filter(maxActiveStreams = 1, meterRegistry)
        val neverCompletes = WebFilterChain { Mono.never() }

        // when
        StepVerifier
            .create(filter.filter(exchange(), neverCompletes))
            .then { assertEquals(1.0, meterRegistry.activeStreams()) }
            .thenCancel()
            .verify()
        StepVerifier.create(filter.filter(exchange(), WebFilterChain { Mono.empty() })).verifyComplete()

        // then
        assertEquals(0.0, meterRegistry.activeStreams())
        assertEquals(0.0, meterRegistry.rejectedAdmissions())
    }

    @Test
    fun `a synchronous handler failure releases its permit`() {
        // given
        val meterRegistry = SimpleMeterRegistry()
        val filter = filter(maxActiveStreams = 1, meterRegistry)
        val failsSynchronously = WebFilterChain { throw IllegalStateException("construction failed") }

        // when
        StepVerifier
            .create(filter.filter(exchange(), failsSynchronously))
            .expectErrorMatches { it is IllegalStateException && it.message == "construction failed" }
            .verify()
        StepVerifier.create(filter.filter(exchange(), WebFilterChain { Mono.empty() })).verifyComplete()

        // then
        assertEquals(0.0, meterRegistry.activeStreams())
        assertEquals(0.0, meterRegistry.rejectedAdmissions())
    }

    @Test
    fun `api disabled voter request is rejected before stream admission`() {
        // given
        val meterRegistry = SimpleMeterRegistry()
        val admissionFilter = filter(maxActiveStreams = 1, meterRegistry)
        val globalFilter = GlobalRequestInterceptor(voterNode(), NodeProperties())
        val exchange = exchange("http://localhost:8080/accounts/stream")
        val admissionChain =
            WebFilterChain { currentExchange ->
                admissionFilter.filter(currentExchange, WebFilterChain { Mono.never() })
            }

        // when
        val result = globalFilter.filter(exchange, admissionChain)

        // then
        StepVerifier
            .create(result)
            .expectErrorMatches { it is ResponseStatusException && it.statusCode == HttpStatus.FORBIDDEN }
            .verify()
        assertEquals(0.0, meterRegistry.activeStreams())
        assertEquals(0.0, meterRegistry.rejectedAdmissions())
    }

    private fun filter(
        maxActiveStreams: Int,
        meterRegistry: SimpleMeterRegistry,
    ): StreamAdmissionFilter =
        StreamAdmissionFilter(
            StreamProperties().apply { this.maxActiveStreams = maxActiveStreams },
            meterRegistry,
        )

    private fun exchange(uri: String = "http://localhost:8080/accounts/stream"): MockServerWebExchange =
        MockServerWebExchange.from(MockServerHttpRequest.get(uri).build())

    private fun voterNode(): AttoNode =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0U,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(ByteArray(32)),
            publicUri = URI("ws://localhost:8082"),
            features = setOf(NodeFeature.VOTING),
        )

    private fun SimpleMeterRegistry.activeStreams(): Double = get("api.stream.active").gauge().value()

    private fun SimpleMeterRegistry.rejectedAdmissions(): Double = get("api.stream.admission.rejections").counter().count()
}
