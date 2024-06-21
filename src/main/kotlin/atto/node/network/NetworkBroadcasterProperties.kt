package atto.node.network

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.network.broadcaster")
class NetworkBroadcasterProperties {
    var cacheExpirationTimeInSeconds: Long? = null
}
