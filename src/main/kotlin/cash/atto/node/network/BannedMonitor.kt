package cash.atto.node.network

import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

@Component
object BannedMonitor {
    private val set = ConcurrentHashMap.newKeySet<InetAddress>()

    @EventListener
    fun store(banned: NodeBanned) {
        set.add(banned.address)
    }

    fun isBanned(address: InetAddress): Boolean = set.contains(address)
}
