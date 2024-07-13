package cash.atto.node

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer
import org.springframework.context.annotation.Configuration
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.reactive.config.WebFluxConfigurer

@Configuration
@AutoConfigureOrder(0)
class ApplicationConfiguration : WebFluxConfigurer {
    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        configurer
            .defaultCodecs()
        configurer
            .defaultCodecs()
    }
}

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["atto.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class ScheduleConfiguration : ThreadPoolTaskSchedulerCustomizer {
    private val logger = KotlinLogging.logger {}

    override fun customize(taskScheduler: ThreadPoolTaskScheduler) {
        taskScheduler.setErrorHandler {
            logger.error(it) { "Scheduled task error" }
        }
    }
}

val attoCoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e ->
        val logger = KotlinLogging.logger {}
        logger.error(e) { "Unexpected internal error" }
    }
