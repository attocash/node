package cash.atto.node.network

import cash.atto.node.network.guardian.PeerBans
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.stereotype.Component

@Component
class NetworkInfoContributor(
    private val connectedPeers: ConnectedPeers,
    private val peerBans: PeerBans,
) : InfoContributor {
    override fun contribute(builder: Info.Builder) {
        val network =
            mapOf(
                "paired" to connectedPeers.snapshot().map { it.node },
                "banned" to peerBans.snapshot().map { it.hostAddress },
            )
        builder.withDetail("network", network)
    }
}
