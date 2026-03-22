package cash.atto.node.network.guardian

import cash.atto.node.EventPublisher
import cash.atto.node.network.NodeBanned
import cash.atto.node.network.NodeUnbanned
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Component
class Unbanner(
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private val bannedAt = ConcurrentHashMap<InetAddress, Instant>()

    @EventListener
    fun track(event: NodeBanned) {
        bannedAt[event.address] = event.timestamp
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    fun review() {
        val now = Instant.now()
        val iterator = bannedAt.entries.iterator()
        while (iterator.hasNext()) {
            val (address, timestamp) = iterator.next()
            if (Duration.between(timestamp, now) >= Duration.ofHours(1)) {
                iterator.remove()
                logger.info { "Unbanning $address after 1 hour" }
                eventPublisher.publish(NodeUnbanned(address))
            }
        }
    }
}
