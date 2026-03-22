package cash.atto.node.network

import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

@Component
object BannedMonitor {
    private val banned = ConcurrentHashMap.newKeySet<InetAddress>()

    @EventListener
    fun ban(event: NodeBanned) {
        banned.add(event.address)
    }

    @EventListener
    fun unban(event: NodeUnbanned) {
        banned.remove(event.address)
    }

    fun isBanned(address: InetAddress): Boolean = banned.contains(address)
}
