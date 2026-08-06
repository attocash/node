package cash.atto.node.bootstrap.discovery

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.channels.Channel
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.bootstrap.discovery")
class DiscoveryProperties {
    var capacity: Int = 10_000
    var headroom: Int = 2_000
    var batchSize: Int = 1_000
    var retryInitialBackoffInSeconds: Long = 1
    var retryMaxBackoffInSeconds: Long = 30

    @PostConstruct
    fun validate() {
        require(capacity > 0) { "Discovery capacity must be positive" }
        require(headroom >= 0) { "Discovery headroom must be non-negative" }
        require(capacity <= Int.MAX_VALUE - headroom) {
            "Discovery capacity plus headroom exceeds the supported channel size"
        }
        require(capacity + headroom < Channel.UNLIMITED) {
            "Discovery capacity plus headroom must be a bounded channel size"
        }
        require(batchSize in 1..minOf(capacity, MAX_BATCH_SIZE)) {
            "Discovery batch size must be positive and no greater than capacity or $MAX_BATCH_SIZE"
        }
        require(retryInitialBackoffInSeconds > 0) {
            "Discovery retry initial backoff must be positive"
        }
        require(retryMaxBackoffInSeconds >= retryInitialBackoffInSeconds) {
            "Discovery retry max backoff must be greater than or equal to initial backoff"
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 1_000
    }
}
