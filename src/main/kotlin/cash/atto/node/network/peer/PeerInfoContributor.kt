package cash.atto.node.network.peer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.stereotype.Component

@ExperimentalCoroutinesApi
@Component
class PeerInfoContributor(
    val peerManager: PeerManager,
) : InfoContributor {
    override fun contribute(builder: Info.Builder) {
        builder.withDetail("peers", peerManager.getPeers())
    }
}
