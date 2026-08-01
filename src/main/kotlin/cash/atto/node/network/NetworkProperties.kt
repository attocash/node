package cash.atto.node.network

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.network")
class NetworkProperties {
    var expirationTimeInSeconds: Long = 300
    var defaultNodes: MutableSet<String> = HashSet()
    var loopbackBlocked: Boolean = true
    var maxActivePeerSessions: Int = 1_000
        set(value) {
            require(value > 0) { "maxActivePeerSessions must be positive" }
            field = value
        }
    var maxPeerSessionsPerAddress: Int = 16
        set(value) {
            require(value > 0) { "maxPeerSessionsPerAddress must be positive" }
            field = value
        }
    var maxPeerSessionsPerPrefix: Int = 64
        set(value) {
            require(value > 0) { "maxPeerSessionsPerPrefix must be positive" }
            field = value
        }
    var maxPeerSessionsPerPublicKey: Int = 1
        set(value) {
            require(value > 0) { "maxPeerSessionsPerPublicKey must be positive" }
            field = value
        }
}
