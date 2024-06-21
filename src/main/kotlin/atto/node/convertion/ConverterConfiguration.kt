package atto.node.convertion

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.MySqlDialect

@Configuration
class ConverterConfiguration {
    @Bean
    fun dbConverter(converters: List<DBConverter<*, *>>): R2dbcCustomConversions =
        R2dbcCustomConversions.of(MySqlDialect.INSTANCE, converters)
}
