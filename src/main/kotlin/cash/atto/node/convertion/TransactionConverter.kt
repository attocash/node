package cash.atto.node.convertion

import cash.atto.commons.AttoBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toBuffer
import cash.atto.node.ApplicationProperties
import cash.atto.node.toBigInteger
import cash.atto.node.transaction.Transaction
import io.r2dbc.spi.Row
import kotlinx.datetime.toJavaInstant
import org.springframework.data.r2dbc.mapping.OutboundRow
import org.springframework.r2dbc.core.Parameter
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class TransactionSerializerDBConverter(
    val properties: ApplicationProperties,
) : DBConverter<Transaction, OutboundRow> {
    override fun convert(transaction: Transaction): OutboundRow {
        val block = transaction.block

        val row = OutboundRow()
        with(row) {
            put("hash", Parameter.from(block.hash))
            put("type", Parameter.from(block.type))
            put("version", Parameter.from(block.version.value))
            put("algorithm", Parameter.from(block.algorithm))
            put("public_key", Parameter.from(block.publicKey))
            put("height", Parameter.from(block.height.value.toBigInteger()))
            put("balance", Parameter.from(block.balance.raw.toBigInteger()))
            put("timestamp", Parameter.from(block.timestamp.toJavaInstant()))
            put("block", Parameter.from(block.toBuffer()))
            put("signature", Parameter.from(transaction.signature))
            put("work", Parameter.from(transaction.work))
            put("received_at", Parameter.from(transaction.receivedAt))
            put(
                "persisted_at",
                Parameter.fromOrEmpty(transaction.persistedAt, Instant::class.java),
            )
        }

        return row
    }
}

@Component
class TransactionDeserializerDBConverter : DBConverter<Row, Transaction> {
    override fun convert(row: Row): Transaction {
        val serializedBlock = row.get("block", ByteArray::class.java)!!.toBuffer()
        val block = AttoBlock.fromBuffer(serializedBlock)!!

        return Transaction(
            block = block,
            signature = AttoSignature(row.get("signature", ByteArray::class.java)!!),
            work = AttoWork(row.get("work", ByteArray::class.java)!!),
            receivedAt = row.get("received_at", Instant::class.java)!!,
            persistedAt = row.get("persisted_at", Instant::class.java)!!,
        )
    }
}
