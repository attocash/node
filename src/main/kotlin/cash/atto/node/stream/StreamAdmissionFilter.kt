package cash.atto.node.stream

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.concurrent.Semaphore

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class StreamAdmissionFilter(
    properties: StreamProperties,
    meterRegistry: MeterRegistry,
) : WebFilter {
    private val maxActiveStreams = properties.maxActiveStreams
    private val permits = Semaphore(maxActiveStreams)
    private val rejectedAdmissions =
        Counter
            .builder("api.stream.admission.rejections")
            .description("Stream requests rejected because all admission slots are active")
            .register(meterRegistry)

    init {
        Gauge
            .builder("api.stream.active", permits) { (maxActiveStreams - it.availablePermits()).toDouble() }
            .description("Currently active HTTP stream requests")
            .register(meterRegistry)
    }

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        if (!exchange.request.path
                .pathWithinApplication()
                .value()
                .endsWith("/stream")
        ) {
            return chain.filter(exchange)
        }

        return Mono.defer {
            if (!permits.tryAcquire()) {
                rejectedAdmissions.increment()
                exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                return@defer exchange.response.setComplete()
            }

            try {
                chain.filter(exchange).doFinally { releasePermit() }
            } catch (throwable: Throwable) {
                releasePermit()
                Mono.error(throwable)
            }
        }
    }

    private fun releasePermit() {
        permits.release()
    }
}
