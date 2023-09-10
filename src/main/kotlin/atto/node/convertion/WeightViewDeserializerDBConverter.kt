package atto.node.convertion

import atto.node.ApplicationProperties
import atto.node.account.WeightView
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoPublicKey
import io.r2dbc.spi.Row
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class WeightViewDeserializerDBConverter(val properties: ApplicationProperties) : DBConverter<Row, WeightView> {
    override fun convert(row: Row): WeightView {
        return WeightView(
            publicKey = AttoPublicKey(row.get("public_key", ByteArray::class.java)!!),
            // Using bigdecimal because it's only type that works for H2 and MYSQL
            weight = AttoAmount(row.get("weight", BigDecimal::class.java)!!.toLong().toULong()),
        )
    }

}