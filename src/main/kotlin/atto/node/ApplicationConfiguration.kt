package atto.node

import cash.atto.commons.serialiazers.json.AttoJson
import kotlinx.coroutines.CoroutineExceptionHandler
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
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
@Profile(value = ["dev", "beta", "live"])
class ScheduleConfiguration

val attoCoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
    val logger = KotlinLogging.logger {}
    logger.error(e) { "Unexpected internal error" }
}