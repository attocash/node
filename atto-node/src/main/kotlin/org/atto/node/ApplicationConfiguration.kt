package org.atto.node

import org.flywaydb.core.Flyway
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableScheduling


@Configuration
class ApplicationConfiguration(val environment: Environment) {

    @Bean(initMethod = "migrate")
    fun flyway(): Flyway {
        return Flyway(
            Flyway.configure()
                .dataSource(
                    environment.getRequiredProperty("spring.flyway.url"),
                    environment.getProperty("spring.flyway.user"),
                    environment.getProperty("spring.flyway.password")
                )
        )
    }
}

@Configuration
@EnableScheduling
@Profile(value = ["dev", "beta", "live"])
class ScheduleConfiguration {
}