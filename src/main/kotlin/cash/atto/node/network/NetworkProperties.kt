package cash.atto.node.network

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.network")
class NetworkProperties {
    var expirationTimeInSeconds: Long = 300
    var defaultNodes: MutableSet<String> = HashSet()
}
