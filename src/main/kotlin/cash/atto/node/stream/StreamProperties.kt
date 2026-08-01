package cash.atto.node.stream

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.api.stream")
class StreamProperties {
    var maxActiveStreams: Int = 32
        set(value) {
            require(value > 0) { "maxActiveStreams must be positive" }
            field = value
        }

    var maxRangesPerRequest: Int = 32
        set(value) {
            require(value > 0) { "maxRangesPerRequest must be positive" }
            field = value
        }

    var maxAddressesPerRequest: Int = 1_000
        set(value) {
            require(value > 0) { "maxAddressesPerRequest must be positive" }
            field = value
        }

    var maxRequestedEntries: Long = 100_000
        set(value) {
            require(value > 0) { "maxRequestedEntries must be positive" }
            field = value
        }
}
