package org.atto.node.convertion

import io.r2dbc.spi.Row
import org.atto.commons.AttoBlock
import org.atto.commons.AttoByteBuffer
import org.atto.commons.AttoSignature
import org.atto.commons.AttoWork
import org.atto.node.transaction.Transaction
import org.springframework.data.r2dbc.mapping.OutboundRow
import org.springframework.r2dbc.core.Parameter
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class TransactionSerializerDBConverter : DBConverter<Transaction, OutboundRow> {

    override fun convert(transaction: Transaction): OutboundRow {
        val block = transaction.block

        val row = OutboundRow()
        with(row) {
            put("hash", Parameter.from(block.hash))
            put("type", Parameter.from(block.type))
            put("version", Parameter.from(block.version))
            put("public_key", Parameter.from(block.publicKey))
            put("height", Parameter.from(block.height))
            put("balance", Parameter.from(block.balance))
            put("timestamp", Parameter.from(block.timestamp.toLocalDateTime()))
            put("block", Parameter.from(block.serialized))
            put("signature", Parameter.from(transaction.signature))
            put("work", Parameter.from(transaction.work))
            put("received_at", Parameter.from(transaction.receivedAt.toLocalDateTime()))
            put(
                "persisted_at",
                Parameter.fromOrEmpty(transaction.persistedAt?.toLocalDateTime(), LocalDateTime::class.java)
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
            receivedAt = row.get("received_at", LocalDateTime::class.java)!!.toInstant(),
            persistedAt = row.get("persisted_at", LocalDateTime::class.java)!!.toInstant(),
        )
    }

}