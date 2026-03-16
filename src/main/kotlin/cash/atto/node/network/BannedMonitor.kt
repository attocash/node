package cash.atto.node.network

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.time.Duration

@Component
object BannedMonitor {
    private val banned =
        Caffeine
            .newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(Duration.ofHours(1))
            .build<InetAddress, Boolean>()
            .asMap()

    @EventListener
    fun store(banned: NodeBanned) {
        BannedMonitor.banned[banned.address] = true
    }

    fun isBanned(address: InetAddress): Boolean = banned.containsKey(address)
}
