package cash.atto.node

import cash.atto.commons.AttoTransaction
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.models.ExternalDocumentation
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import jakarta.annotation.PostConstruct
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference
import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.context.event.ApplicationEventMulticaster
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.scheduling.annotation.EnableScheduling

@ImportRuntimeHints(SpringDocWorkaround1::class, SpringDocWorkaround2::class)
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
    fun springShopOpenAPI(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Atto Node API")
                    .description(
                        "Atto is a high-performance cryptocurrency focused on instant, feeless, " +
                            "and scalable digital cash; this interface is the entry point to the network, " +
                            "allowing clients to publish and receive blocks, query account data, and participate in the network.",
                    ).version("v1.0.0"),
            ).externalDocs(
                ExternalDocumentation()
                    .description("Integration Docs")
                    .url("https://atto.cash/docs/integration"),
            )
}

class SpringDocWorkaround1 : RuntimeHintsRegistrar {
    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?,
    ) {
        hints.reflection().registerType(
            TypeReference.of("org.springframework.core.convert.support.GenericConversionService\$Converters"),
            *MemberCategory.entries.toTypedArray(),
        )
    }
}


class SpringDocWorkaround2 : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, cl: ClassLoader?) {

        hints.reflection().registerType(
            AttoTransaction::class.java,
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS
        )

        hints.reflection().registerType(
            TypeReference.of("cash.atto.commons.AttoTransaction[]"),
            MemberCategory.UNSAFE_ALLOCATED
        )
    }
}
