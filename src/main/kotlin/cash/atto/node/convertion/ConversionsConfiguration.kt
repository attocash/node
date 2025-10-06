package cash.atto.node.convertion

import cash.atto.commons.spring.conversion.AttoConverters
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.MySqlDialect

@Configuration
class ConversionsConfiguration {
    @Bean
    fun customConversions(): R2dbcCustomConversions {
        val nodeConverters =
            listOf(
                TransactionSerializerDBConverter(),
                TransactionDeserializerDBConverter(),
                UncheckedTransactionSerializerDBConverter(),
                UncheckedTransactionDeserializerDBConverter(),
                VoteSerializerDBConverter(),
                VoteDeserializerDBConverter(),
            )

        val converters = AttoConverters.all + nodeConverters

        return R2dbcCustomConversions.of(MySqlDialect.INSTANCE, converters)
    }
}
