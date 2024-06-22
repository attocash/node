package cash.atto.node.network.peer

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.peer")
class PeerProperties {
    var expirationTimeInSeconds: Long = 300
    var defaultNodes: MutableSet<String> = HashSet()
}
