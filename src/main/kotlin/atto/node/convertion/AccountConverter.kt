package atto.node.convertion

import atto.node.ApplicationProperties
import atto.node.account.Account
import atto.node.toBigInteger
import atto.node.toULong
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import io.r2dbc.spi.Row
import org.springframework.data.r2dbc.mapping.OutboundRow
import org.springframework.r2dbc.core.Parameter
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.time.Instant

@Component
class AccountSerializerDBConverterer(val properties: ApplicationProperties) : DBConverter<Account, OutboundRow> {

    override fun convert(account: Account): OutboundRow {
        val row = OutboundRow()
        with(row) {
            put("public_key", Parameter.from(account.publicKey))
            put("version", Parameter.from(account.version))
            put("height", Parameter.from(account.height.toBigInteger()))
            put("balance", Parameter.from(account.balance.raw.toBigInteger()))
            put("last_transaction_hash", Parameter.from(account.lastTransactionHash))
            put("last_transaction_timestamp", Parameter.from(account.lastTransactionTimestamp))
            put("representative", Parameter.from(account.representative))
            put("persisted_at", Parameter.fromOrEmpty(account.persistedAt, Instant::class.java))
        }

        return row
    }

}

@Component
class AccountDeserializerDBConverterer(val properties: ApplicationProperties) : DBConverter<Row, Account> {
    override fun convert(row: Row): Account {
        return Account(
            publicKey = AttoPublicKey(row.get("public_key", ByteArray::class.java)!!),
            version = row.get("version", Short::class.javaObjectType)!!.toUShort(),
            height = row.get("height", BigInteger::class.javaObjectType)!!.toULong(),
            balance = AttoAmount(row.get("balance", BigInteger::class.javaObjectType)!!.toULong()),
            lastTransactionHash = AttoHash(row.get("last_transaction_hash", ByteArray::class.java)!!),
            lastTransactionTimestamp = row.get("last_transaction_timestamp", Instant::class.java)!!,
            representative = AttoPublicKey(row.get("representative", ByteArray::class.java)!!),
            persistedAt = row.get("persisted_at", Instant::class.java)!!,
            updatedAt = row.get("updated_at", Instant::class.java)!!,
        )
    }

}