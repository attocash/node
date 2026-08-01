package cash.atto.node.bootstrap.discovery

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.bootstrap.discovery")
class DiscoveryProperties {
    var queueMaxSize: Int = 100_000
        set(value) {
            require(value > 0) { "queueMaxSize must be positive" }
            field = value
        }

    var batchSize: Int = 1_000
        set(value) {
            require(value > 0) { "batchSize must be positive" }
            field = value
        }

    var maxBatchesPerFlush: Int = 10
        set(value) {
            require(value > 0) { "maxBatchesPerFlush must be positive" }
            field = value
        }
}
