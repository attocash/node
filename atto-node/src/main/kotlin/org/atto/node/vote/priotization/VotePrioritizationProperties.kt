package org.atto.node.vote.priotization

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


@Configuration
@ConfigurationProperties(prefix = "atto.vote.prioritization")
class VotePrioritizationProperties {
    var groupMaxSize: Int? = null
    var cacheMaxSize: Long? = null
}