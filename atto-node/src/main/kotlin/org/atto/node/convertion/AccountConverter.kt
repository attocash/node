package org.atto.node.convertion

import io.r2dbc.spi.Row
import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.node.account.Account
import org.springframework.data.r2dbc.mapping.OutboundRow
import org.springframework.r2dbc.core.Parameter
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
class AccountSerializerDBConverter : DBConverter<Account, OutboundRow> {

    override fun convert(account: Account): OutboundRow {
        val row = OutboundRow()
        with(row) {
            put("public_key", Parameter.from(account.publicKey))
            put("version", Parameter.from(account.version))
            put("height", Parameter.from(account.height))
            put("balance", Parameter.from(account.balance))
            put("last_transaction_hash", Parameter.from(account.lastTransactionHash))
            put("last_transaction_timestamp", Parameter.from(account.lastTransactionTimestamp))
            put("representative", Parameter.from(account.representative))
            put("persisted_at", Parameter.fromOrEmpty(account.persistedAt, Instant::class.java))
            put("updated_at", Parameter.fromOrEmpty(account.updatedAt, Instant::class.java))
        }

        return row
    }

}

@Component
class AccountDeserializerDBConverter : DBConverter<Row, Account> {
    override fun convert(row: Row): Account {
        return Account(
            publicKey = AttoPublicKey(row.get("public_key", ByteArray::class.java)!!),
            version = row.get("version", Short::class.javaObjectType)!!.toUShort(),
            height = row.get("height", Long::class.javaObjectType)!!.toULong(),
            balance = AttoAmount(row.get("balance", Long::class.javaObjectType)!!.toULong()),
            lastTransactionHash = AttoHash(row.get("last_transaction_hash", ByteArray::class.java)!!),
            lastTransactionTimestamp = row.get("last_transaction_timestamp", LocalDateTime::class.java)!!
                .toInstant(ZoneOffset.UTC),
            representative = AttoPublicKey(row.get("representative", ByteArray::class.java)!!),
            persistedAt = row.get("persisted_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
            updatedAt = row.get("updated_at", LocalDateTime::class.java)!!.toInstant(ZoneOffset.UTC),
        )
    }

}