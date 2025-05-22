package cash.atto.node.network

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@Component
class NetworkHealthIndicator : HealthIndicator {
    private val logger = KotlinLogging.logger {}

    private val peerCount = AtomicInteger(0)
    private var lastDisconnect = Instant.now()

    @EventListener
    fun add(nodeEvent: NodeConnected) {
        peerCount.incrementAndGet()
    }

    @EventListener
    fun remove(nodeEvent: NodeDisconnected) {
        peerCount.decrementAndGet()
    }

    override fun health(): Health =
        if (peerCount.get() > 0 || lastDisconnect > Instant.now().minus(Duration.ofMinutes(5))) {
            Health.up().withDetail("peers", peerCount.get().toString()).build()
        } else {
            logger.warn { "Node is not connected to any peer" }
            Health.down().build()
        }
}
