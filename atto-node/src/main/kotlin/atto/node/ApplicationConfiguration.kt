package atto.node

import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor


@Configuration
@EnableAsync(proxyTargetClass = true)
@AutoConfigureOrder(0)
class ApplicationConfiguration {

    @Bean
    fun threadPoolTaskExecutor(): ThreadPoolTaskExecutor {
        return ThreadPoolTaskExecutor().apply {
            this.corePoolSize = 1
            this.setThreadNamePrefix(Thread.currentThread().name)
        }
    }

    @Bean(initMethod = "migrate")
    fun flyway(environment: Environment): Flyway {
        return Flyway(
            Flyway.configure()
                .dataSource(
                    environment.getRequiredProperty("spring.flyway.url"),
                    environment.getProperty("spring.flyway.username"),
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