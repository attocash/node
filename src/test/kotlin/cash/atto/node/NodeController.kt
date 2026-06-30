package cash.atto.node

import cash.atto.commons.AttoPublicKey
import cash.atto.node.network.NetworkProcessor
import cash.atto.node.network.NodeConnectionManager
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/nodes")
@Profile("default")
class NodeController(
    private val nodeConnectionManager: NodeConnectionManager,
    private val networkProcessor: NetworkProcessor,
) {
    @GetMapping("/peers")
    suspend fun get(): Collection<AttoPublicKey> = nodeConnectionManager.connectedPublicKeys

    @PostMapping("/bootstrap")
    suspend fun bootstrap() {
        networkProcessor.bootstrap()
    }
}
