package atto.node.convertion

import atto.node.ApplicationProperties
import atto.node.account.WeightView
import cash.atto.commons.AttoPublicKey
import io.r2dbc.spi.Row
import org.springframework.stereotype.Component
import java.math.BigInteger

@Component
class WeightViewDeserializerDBConverter(val properties: ApplicationProperties) : DBConverter<Row, WeightView> {
    override fun convert(row: Row): WeightView {
        return WeightView(
            publicKey = AttoPublicKey(row.get("public_key", ByteArray::class.java)!!),
            weight = row.get("weight", BigInteger::class.java)!!,
        )
    }

}