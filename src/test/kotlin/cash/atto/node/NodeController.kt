package cash.atto.node

import cash.atto.commons.AttoPublicKey
import cash.atto.node.network.peer.PeerManager
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/nodes")
@Profile("default")
class NodeController(
    private val peerManager: PeerManager,
) {
    @GetMapping("/peers")
    suspend fun get(): List<AttoPublicKey> = peerManager.getPeers().map { it.node.publicKey }
}
