package atto.node.convertion

import atto.node.ApplicationProperties
import atto.node.receivable.Receivable
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import io.r2dbc.spi.Row
import org.springframework.data.r2dbc.mapping.OutboundRow
import org.springframework.r2dbc.core.Parameter
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime

@Component
class ReceivableSerializerDBConverter(val properties: ApplicationProperties) : DBConverter<Receivable, OutboundRow> {

    override fun convert(receivable: Receivable): OutboundRow {
        val row = OutboundRow()
        with(row) {
            put("hash", Parameter.from(receivable.hash))
            put("receiver_public_key", Parameter.from(receivable.receiverPublicKey))
            put("amount", Parameter.from(receivable.amount.raw.toDB(properties.db)))
            put("persisted_at", Parameter.fromOrEmpty(receivable.persistedAt?.toLocalDateTime(), Instant::class.java))
        }

        return row
    }

}

@Component
class AccountReceivableDeserializerDBConverter(val properties: ApplicationProperties) : DBConverter<Row, Receivable> {
    override fun convert(row: Row): Receivable {
        return Receivable(
            hash = AttoHash(row.get("hash", ByteArray::class.java)!!),
            receiverPublicKey = AttoPublicKey(row.get("receiver_public_key", ByteArray::class.javaObjectType)!!),
            amount = AttoAmount(row.toULong(properties.db, "amount")),
            persistedAt = row.get("persisted_at", LocalDateTime::class.java)!!.toInstant(),
        )
    }

}