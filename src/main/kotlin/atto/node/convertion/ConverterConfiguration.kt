package atto.node.convertion

import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.MySqlDialect
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

@Configuration
class ConverterConfiguration {
    @Bean
    fun jackson2ObjectMapperBuilder(
        serializers: List<JsonSerializer<*>>,
        deserializers: List<JsonDeserializer<*>>
    ): Jackson2ObjectMapperBuilder {
        return Jackson2ObjectMapperBuilder()
            .serializers(* serializers.toTypedArray())
            .deserializers(* deserializers.toTypedArray())
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    @Bean
    fun dbConverter(converters: List<DBConverter<*, *>>): R2dbcCustomConversions {
        return R2dbcCustomConversions.of(MySqlDialect.INSTANCE, converters);
    }
}