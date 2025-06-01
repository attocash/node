package cash.atto.node.network

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class NetworkHealthIndicator(
    private val connectionManager: NodeConnectionManager,
) : HealthIndicator {
    private val logger = KotlinLogging.logger {}

    private var lastDisconnect = Instant.now()

    override fun health(): Health =
        if (connectionManager.connectionCount > 0 || lastDisconnect > Instant.now().minus(Duration.ofMinutes(5))) {
            Health.up().withDetail("peers", connectionManager.connectionCount.toString()).build()
        } else {
            logger.warn { "Node is not connected to any peer" }
            Health.down().build()
        }
}
