package cash.atto.node

import io.swagger.v3.oas.models.ExternalDocumentation
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.config.WebFluxConfigurer

@Configuration
@EnableScheduling
@AutoConfigureOrder(0)
class ApplicationConfiguration : WebFluxConfigurer {
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
                    .description("Docs")
                    .url("https://atto.cash/docs"),
            )

    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        configurer
            .defaultCodecs()
        configurer
            .defaultCodecs()
    }
}
