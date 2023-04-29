package atto.node.network.peer

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


@Configuration
@ConfigurationProperties(prefix = "atto.handshake")
class HandshakeProperties {
    var expirationTimeInSeconds: Long? = null
}