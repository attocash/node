package atto.node.convertion

import atto.node.ApplicationProperties
import atto.node.toBigInteger
import atto.node.transaction.Transaction
import cash.atto.commons.AttoBlock
import cash.atto.commons.AttoByteBuffer
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import io.r2dbc.spi.Row
import kotlinx.datetime.toJavaInstant
import org.springframework.data.r2dbc.mapping.OutboundRow
import org.springframework.r2dbc.core.Parameter
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class TransactionSerializerDBConverter(val properties: ApplicationProperties) : DBConverter<Transaction, OutboundRow> {

    override fun convert(transaction: Transaction): OutboundRow {
        val block = transaction.block

        val row = OutboundRow()
        with(row) {
            put("hash", Parameter.from(block.hash))
            put("type", Parameter.from(block.type))
            put("version", Parameter.from(block.version))
            put("public_key", Parameter.from(block.publicKey))
            put("height", Parameter.from(block.height.toBigInteger()))
            put("balance", Parameter.from(block.balance.raw.toBigInteger()))
            put("timestamp", Parameter.from(block.timestamp.toJavaInstant()))
            put("block", Parameter.from(block.toByteBuffer()))
            put("signature", Parameter.from(transaction.signature))
            put("work", Parameter.from(transaction.work))
            put("received_at", Parameter.from(transaction.receivedAt))
            put(
                "persisted_at",
                Parameter.fromOrEmpty(transaction.persistedAt, Instant::class.java)
            )
        }

        return row
    }

}

@Component
class TransactionDeserializerDBConverter : DBConverter<Row, Transaction> {
    override fun convert(row: Row): Transaction {
        val serializedBlock = AttoByteBuffer(row.get("block", ByteArray::class.java)!!)
        val block = AttoBlock.fromByteBuffer(serializedBlock)!!

        return Transaction(
            block = block,
            signature = AttoSignature(row.get("signature", ByteArray::class.java)!!),
            work = AttoWork(row.get("work", ByteArray::class.java)!!),
            receivedAt = row.get("received_at", Instant::class.java)!!,
            persistedAt = row.get("persisted_at", Instant::class.java)!!,
        )
    }

}