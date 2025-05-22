package cash.atto.node.network

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class NetworkHealthIndicator : HealthIndicator {
    private val logger = KotlinLogging.logger {}

    private val peers = ConcurrentHashMap.newKeySet<URI>()

    private var lastDisconnect = Instant.now()

    @EventListener
    fun add(nodeEvent: NodeConnected) {
        val node = nodeEvent.node
        if (!node.isHistorical()) {
            return
        }
        peers.add(node.publicUri)
    }

    @EventListener
    fun remove(nodeEvent: NodeDisconnected) {
        val node = nodeEvent.node
        peers.remove(node.publicUri)
        lastDisconnect = Instant.now()
    }

    override fun health(): Health {
        return if (peers.isNotEmpty() || lastDisconnect > Instant.now().minus(Duration.ofMinutes(5))) {
            Health.up().withDetail("peers", peers.size.toString()).build()
        } else {
            logger.warn { "Node is not connected to any peer" }
            Health.down().build()
        }
    }
}
