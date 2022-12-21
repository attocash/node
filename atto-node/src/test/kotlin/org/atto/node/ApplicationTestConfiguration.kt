package org.atto.node

import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.handler.logging.LogLevel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.ClientCodecConfigurer
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.transport.logging.AdvancedByteBufFormat


@Configuration
class ApplicationTestConfiguration {
    @Bean
    fun exchangeStrategies(objectMapper: ObjectMapper): ExchangeStrategies {
        return ExchangeStrategies.builder()
            .codecs { configurer: ClientCodecConfigurer ->
                configurer.defaultCodecs()
                    .jackson2JsonEncoder(Jackson2JsonEncoder(objectMapper))
                configurer.defaultCodecs()
                    .jackson2JsonDecoder(Jackson2JsonDecoder(objectMapper))
            }
            .build()
    }

    @Bean
    fun webClient(exchangeStrategies: ExchangeStrategies): WebClient {
        val httpClient: HttpClient = HttpClient.create()
            .wiretap(
                this.javaClass.canonicalName,
                LogLevel.DEBUG,
                AdvancedByteBufFormat.TEXTUAL
            )
        val conn: ClientHttpConnector = ReactorClientHttpConnector(httpClient)
        return WebClient.builder()
            .exchangeStrategies(exchangeStrategies)
//            .clientConnector(conn) // uncomment it to enable debugging
            .build()
    }

}