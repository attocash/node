package atto.node

import io.r2dbc.spi.Option
import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.boot.autoconfigure.flyway.FlywayProperties
import org.springframework.boot.autoconfigure.r2dbc.R2dbcConnectionDetails
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling


@Configuration
@AutoConfigureOrder(0)
@EnableConfigurationProperties(FlywayProperties::class)
class ApplicationConfiguration {

    @Bean(initMethod = "migrate")
    fun flyway(connectionDetails: R2dbcConnectionDetails): Flyway {
        val options = connectionDetails.connectionFactoryOptions
        val driver = options.getRequiredValue(Option.valueOf<String>("driver")) as String
        val host = options.getRequiredValue(Option.valueOf<String>("host")) as String
        val port = options.getRequiredValue(Option.valueOf<Int>("port")) as Int
        val database = options.getRequiredValue(Option.valueOf<String>("database")) as String
        val user = options.getRequiredValue(Option.valueOf<String>("user")) as String
        val password = options.getRequiredValue(Option.valueOf<String>("password")) as String
        return Flyway(
            Flyway.configure()
                .dataSource(
                    "jdbc:${driver}://${host}:${port}/${database}",
                    user,
                    password
                )
        )
    }
}


@Configuration
@EnableScheduling
@Profile(value = ["dev", "beta", "live"])
class ScheduleConfiguration {
}