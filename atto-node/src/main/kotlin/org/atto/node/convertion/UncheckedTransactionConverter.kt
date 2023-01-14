package org.atto.node.convertion

import io.r2dbc.spi.Row
import org.atto.commons.AttoBlock
import org.atto.commons.AttoByteBuffer
import org.atto.commons.AttoSignature
import org.atto.commons.AttoWork
import org.atto.node.bootstrap.unchecked.UncheckedTransaction
import org.springframework.data.r2dbc.mapping.OutboundRow
import org.springframework.r2dbc.core.Parameter
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class UncheckedTransactionSerializerDBConverter : DBConverter<UncheckedTransaction, OutboundRow> {

    override fun convert(uncheckedTransaction: UncheckedTransaction): OutboundRow {
        val block = uncheckedTransaction.block
        val row = OutboundRow()
        with(row) {
            put("hash", Parameter.from(uncheckedTransaction.hash))
            put("public_key", Parameter.from(block.publicKey))
            put("height", Parameter.from(block.height))
            put("block", Parameter.from(block.serialized))
            put("signature", Parameter.from(uncheckedTransaction.signature))
            put("work", Parameter.from(uncheckedTransaction.work))
            put("received_at", Parameter.from(uncheckedTransaction.receivedAt.toLocalDateTime()))
            put(
                "persisted_at",
                Parameter.fromOrEmpty(uncheckedTransaction.persistedAt?.toLocalDateTime(), LocalDateTime::class.java)
            )
        }

        return row
    }

}

@Component
class UncheckedTransactionDeserializerDBConverter : DBConverter<Row, UncheckedTransaction> {
    override fun convert(row: Row): UncheckedTransaction {
        val serializedBlock = AttoByteBuffer(row.get("block", ByteArray::class.java)!!)
        val block = AttoBlock.fromByteBuffer(serializedBlock)!!

        return UncheckedTransaction(
            block = block,
            signature = AttoSignature(row.get("signature", ByteArray::class.java)!!),
            work = AttoWork(row.get("work", ByteArray::class.java)!!),
            receivedAt = row.get("received_at", LocalDateTime::class.java)!!.toInstant(),
            persistedAt = row.get("persisted_at", LocalDateTime::class.java)!!.toInstant(),
        )
    }

}