package cash.atto.node.convertion

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toULong
import cash.atto.node.vote.weight.Weight
import io.r2dbc.spi.Row
import org.springframework.core.convert.converter.Converter
import java.math.BigInteger
import java.time.LocalDateTime
import java.time.ZoneOffset

class WeightDeserializerDBConverter : Converter<Row, Weight> {
    override fun convert(row: Row): Weight =
        Weight(
            representativePublicKey = AttoPublicKey(row.get("representative_public_key", ByteArray::class.java)!!),
            weight = AttoAmount(row.get("weight", BigInteger::class.java)!!.toULong()),
            lastVoteTimestamp = row.get("last_vote_timestamp", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
        )
}
