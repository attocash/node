package atto.node

import cash.atto.commons.serialiazers.json.AttoJson
import io.netty.handler.logging.LogLevel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.ClientCodecConfigurer
import org.springframework.http.codec.json.KotlinSerializationJsonDecoder
import org.springframework.http.codec.json.KotlinSerializationJsonEncoder
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import org.testcontainers.containers.MySQLContainer
import reactor.netty.http.client.HttpClient
import reactor.netty.transport.logging.AdvancedByteBufFormat

@Configuration
class ApplicationTestConfiguration {
    @Bean
    fun exchangeStrategies(): ExchangeStrategies {
        return ExchangeStrategies
            .builder()
            .codecs { configurer: ClientCodecConfigurer ->
                configurer
                    .defaultCodecs()
                    .kotlinSerializationJsonEncoder(KotlinSerializationJsonEncoder(AttoJson))
                configurer
                    .defaultCodecs()
                    .kotlinSerializationJsonDecoder(KotlinSerializationJsonDecoder(AttoJson))
            }.build()
    }

    @Bean
    fun webClient(exchangeStrategies: ExchangeStrategies): WebClient {
        val httpClient: HttpClient =
            HttpClient
                .create()
                .wiretap(
                    this.javaClass.canonicalName,
                    LogLevel.DEBUG,
                    AdvancedByteBufFormat.TEXTUAL,
                )
        val connector: ClientHttpConnector = ReactorClientHttpConnector(httpClient)
        return WebClient
            .builder()
            .exchangeStrategies(exchangeStrategies)
            .clientConnector(connector)
            .build()
    }

    @Bean
    @ServiceConnection
    @ConditionalOnProperty("atto.test.mysql-container.enabled", havingValue = "true", matchIfMissing = true)
    fun mysqlContainer(): MySQLContainer<*> {
        val container = MySQLContainer("mysql:8.2")
        container.withUsername("root")
        return container
    }
}
