package atto.node

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


enum class DB {
    MYSQL, H2
}

@Configuration
@ConfigurationProperties(prefix = "atto")
class ApplicationProperties {
    var db: DB = DB.MYSQL
    var useXForwardedFor: Boolean = false
}