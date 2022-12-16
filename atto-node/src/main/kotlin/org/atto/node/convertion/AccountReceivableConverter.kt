package org.atto.node.convertion

import io.r2dbc.spi.Row
import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.node.receivable.AccountReceivable
import org.springframework.data.r2dbc.mapping.OutboundRow
import org.springframework.r2dbc.core.Parameter
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime

@Component
class AccountReceivableSerializerDBConverter : DBConverter<AccountReceivable, OutboundRow> {

    override fun convert(receivable: AccountReceivable): OutboundRow {
        val row = OutboundRow()
        with(row) {
            put("hash", Parameter.from(receivable.hash))
            put("receiver_public_key", Parameter.from(receivable.receiverPublicKey))
            put("amount", Parameter.from(receivable.amount.raw.toLong()))
            put("persisted_at", Parameter.fromOrEmpty(receivable.persistedAt?.toLocalDateTime(), Instant::class.java))
        }

        return row
    }

}

@Component
class AccountReceivableDeserializerDBConverter : DBConverter<Row, AccountReceivable> {
    override fun convert(row: Row): AccountReceivable {
        return AccountReceivable(
            hash = AttoHash(row.get("hash", ByteArray::class.java)!!),
            receiverPublicKey = AttoPublicKey(row.get("receiver_public_key", ByteArray::class.javaObjectType)!!),
            amount = AttoAmount(row.get("amount", Long::class.javaObjectType)!!.toULong()),
            persistedAt = row.get("persisted_at", LocalDateTime::class.java)!!.toInstant(),
        )
    }

}