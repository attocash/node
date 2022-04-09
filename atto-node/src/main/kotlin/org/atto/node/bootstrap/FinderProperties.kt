package org.atto.node.bootstrap

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


@Configuration
@ConfigurationProperties(prefix = "atto.transaction.finder")
class FinderProperties {
    var cacheExpirationTimeInSeconds: Long = 300
    var cacheMaxSize: Long = 10_000
}