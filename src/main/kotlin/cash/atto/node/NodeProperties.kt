package cash.atto.node

import cash.atto.commons.AttoNetwork
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.node")
class NodeProperties {
    var forceVoter: Boolean = false
    var forceHistorical: Boolean = false
    var forceApi: Boolean = false
    var network: AttoNetwork? = null
    var publicUri: String? = null
}
