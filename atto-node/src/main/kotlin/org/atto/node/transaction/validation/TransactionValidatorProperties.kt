package org.atto.node.transaction.validation

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


@Configuration
@ConfigurationProperties(prefix = "atto.transaction.validator")
class TransactionValidatorProperties {
    var groupMaxSize: Int? = null
    var cacheMaxSize: Int? = null
    var cacheExpirationTimeInSeconds: Long? = null
}