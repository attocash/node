package cash.atto.node.election

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.vote.election")
class ElectionProperties {
    var expiringAfterTimeInSeconds: Long? = null
    var expiredAfterTimeInSeconds: Long? = null
    var processingRetryMaxAttempts: Int = 5
    var processingRetryInitialBackoffInSeconds: Long = 1
    var processingRetryMaxBackoffInSeconds: Long = 30
}
