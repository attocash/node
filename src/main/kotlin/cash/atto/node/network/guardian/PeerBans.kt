package cash.atto.node.network.guardian

import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.network.NodeBanned
import cash.atto.node.network.NodeUnbanned
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@Component
class PeerBans(
    private val eventPublisher: EventPublisher,
    private val clock: Clock,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}
    private val bannedAt = HashMap<InetAddress, Instant>()

    @Synchronized
    fun ban(address: InetAddress) {
        val now = clock.instant()
        bannedAt[address] = maxOf(bannedAt[address] ?: now, now)
        eventPublisher.publish(NodeBanned(address, now))
    }

    @Synchronized
    fun isBanned(address: InetAddress): Boolean = bannedAt.containsKey(address)

    @Synchronized
    fun snapshot(): Set<InetAddress> = bannedAt.keys.toSet()

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    @Synchronized
    fun review() {
        val now = clock.instant()
        val expired = bannedAt.filterValues { Duration.between(it, now) >= Duration.ofHours(1) }.keys
        expired.forEach { address ->
            bannedAt.remove(address)
            logger.info { "Unbanning $address after 1 hour" }
            eventPublisher.publish(NodeUnbanned(address, now))
        }
    }

    @Synchronized
    override fun clear() {
        bannedAt.clear()
    }
}
