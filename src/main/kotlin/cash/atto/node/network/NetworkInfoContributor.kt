package cash.atto.node.network

import cash.atto.protocol.AttoNode
import kotlinx.serialization.ExperimentalSerializationApi
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalSerializationApi::class)
@Component
class NetworkInfoContributor : InfoContributor {
    private val paired = ConcurrentHashMap<InetSocketAddress, AttoNode>()
    private val banned = ConcurrentHashMap.newKeySet<InetAddress>()

    @EventListener
    fun add(event: NodeConnected) {
        paired[event.connectionSocketAddress] = event.node
    }

    @EventListener
    fun remove(event: NodeDisconnected) {
        paired.remove(event.connectionSocketAddress)
    }

    @EventListener
    fun ban(event: NodeBanned) {
        banned.add(event.address)
    }

    @EventListener
    fun unban(event: NodeUnbanned) {
        banned.remove(event.address)
    }

    override fun contribute(builder: Info.Builder) {
        val network =
            mapOf(
                "paired" to paired.values,
                "banned" to banned.map { it.hostAddress },
            )
        builder.withDetail("network", network)
    }
}
