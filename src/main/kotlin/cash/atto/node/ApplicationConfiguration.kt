package cash.atto.node

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.models.ExternalDocumentation
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.ApplicationEventMulticaster
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
@AutoConfigureOrder(0)
class ApplicationConfiguration {
    val logger = KotlinLogging.logger {}

    @PostConstruct
    fun init() {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            logger.error(e) { "Uncaught on thread ${Thread.currentThread().name}" }
        }
    }

    @Bean
    fun applicationEventMulticaster(): ApplicationEventMulticaster {
        val multicaster = SimpleApplicationEventMulticaster()

        multicaster.setTaskExecutor { task ->
            Thread.ofVirtual().start(task)
        }
        multicaster.setErrorHandler {
            logger.error(it) { it.message }
        }
        return multicaster
    }

    @Bean
    fun openApi(environment: Environment): OpenAPI {
        val version = environment.getProperty("spring.application.version").ifEmpty { "dev" }
        return OpenAPI()
            .info(
                Info()
                    .title("Atto Node API")
                    .description(
                        "Atto is a high-performance cryptocurrency focused on instant, feeless, " +
                            "and scalable digital cash; this interface is the entry point to the network, " +
                            "allowing clients to publish and receive blocks, query account data, and participate in the network.",
                    ).version(version),
            ).externalDocs(
                ExternalDocumentation()
                    .description("Integration Docs")
                    .url("https://atto.cash/docs/integration"),
            )
    }
}
