package org.atto.node.vote.validator

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


@Configuration
@ConfigurationProperties(prefix = "atto.vote.validator")
class VoteValidatorProperties {
    var groupMaxSize: Int? = null
    var cacheMaxSize: Long? = null
    var cacheExpirationTimeInSeconds: Long? = null
}