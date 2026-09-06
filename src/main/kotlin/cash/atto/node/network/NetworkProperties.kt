package cash.atto.node.network

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.network")
class NetworkProperties {
    var defaultNodes: MutableSet<String> = HashSet()
    var loopbackBlocked: Boolean = true
}
