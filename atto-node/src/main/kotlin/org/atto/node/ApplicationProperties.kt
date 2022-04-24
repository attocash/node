package org.atto.node

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


@Configuration
@ConfigurationProperties(prefix = "atto")
class ApplicationProperties {
    var genesis: String? = null
}