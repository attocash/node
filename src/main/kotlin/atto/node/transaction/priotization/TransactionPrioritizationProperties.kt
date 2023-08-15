package atto.node.transaction.priotization

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


@Configuration
@ConfigurationProperties(prefix = "atto.transaction.prioritization")
class TransactionPrioritizationProperties {
    var groupMaxSize: Int? = null
}