package cash.atto.node

import cash.atto.commons.AttoPublicKey
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ConcurrentHashMap

@RestController
@RequestMapping("/nodes")
@Profile("default")
class NodeController {
    private val peers = ConcurrentHashMap.newKeySet<AttoPublicKey>()

    @EventListener
    fun add(nodeEvent: NodeConnected) {
        peers.add(nodeEvent.node.publicKey)
    }

    @EventListener
    fun remove(nodeEvent: NodeDisconnected) {
        peers.remove(nodeEvent.node.publicKey)
    }

    @GetMapping("/peers")
    suspend fun get(): Collection<AttoPublicKey> = peers
}
