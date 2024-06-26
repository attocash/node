package cash.atto.node.transaction

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.transaction")
class TransactionProperties {
    var genesis: String? = null
}
