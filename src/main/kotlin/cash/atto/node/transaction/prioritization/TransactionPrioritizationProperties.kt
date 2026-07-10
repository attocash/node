package cash.atto.node.transaction.prioritization

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.transaction.prioritization")
class TransactionPrioritizationProperties {
    var groupMaxSize: Int? = null
    var maxActiveElections: Int? = null
    var dependencyMaxSize: Int = 1_000
        set(value) {
            require(value > 0) { "dependencyMaxSize must be positive" }
            field = value
        }

    var bufferMaxSize: Int = 10_000
        set(value) {
            require(value > 0) { "bufferMaxSize must be positive" }
            field = value
        }
}
