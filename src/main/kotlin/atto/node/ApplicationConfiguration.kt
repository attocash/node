package atto.node

import cash.atto.commons.serialiazers.json.AttoJson
import kotlinx.coroutines.CoroutineExceptionHandler
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.KotlinSerializationJsonDecoder
import org.springframework.http.codec.json.KotlinSerializationJsonEncoder
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.config.WebFluxConfigurer


@Configuration
@AutoConfigureOrder(0)
class ApplicationConfiguration : WebFluxConfigurer {

    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        configurer.defaultCodecs()
            .kotlinSerializationJsonEncoder(KotlinSerializationJsonEncoder(AttoJson))
        configurer.defaultCodecs()
            .kotlinSerializationJsonDecoder(KotlinSerializationJsonDecoder(AttoJson))
    }
}


@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["atto.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class ScheduleConfiguration

val attoCoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
    val logger = KotlinLogging.logger {}
    logger.error(e) { "Unexpected internal error" }
}