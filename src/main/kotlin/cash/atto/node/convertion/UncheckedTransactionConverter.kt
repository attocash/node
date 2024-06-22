package cash.atto.node.convertion

import cash.atto.commons.*
import cash.atto.node.bootstrap.unchecked.UncheckedTransaction
import io.r2dbc.spi.Row
import org.springframework.data.r2dbc.mapping.OutboundRow
import org.springframework.r2dbc.core.Parameter
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class UncheckedTransactionSerializerDBConverter : DBConverter<UncheckedTransaction, OutboundRow> {
    override fun convert(uncheckedTransaction: UncheckedTransaction): OutboundRow {
        val block = uncheckedTransaction.block
        val row = OutboundRow()
        with(row) {
            put("hash", Parameter.from(uncheckedTransaction.hash))
            put("algorithm", Parameter.from(block.algorithm))
            put("public_key", Parameter.from(block.publicKey))
            put("height", Parameter.from(block.height))
            put(
                "previous",
                Parameter.fromOrEmpty(if (block is PreviousSupport) block.previous else null, AttoHash::class.java),
            )
            put("block", Parameter.from(block.toByteBuffer()))
            put("signature", Parameter.from(uncheckedTransaction.signature))
            put("work", Parameter.from(uncheckedTransaction.work))
            put("received_at", Parameter.from(uncheckedTransaction.receivedAt))
            put(
                "persisted_at",
                Parameter.fromOrEmpty(uncheckedTransaction.persistedAt, Instant::class.java),
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
            receivedAt = row.get("received_at", Instant::class.java)!!,
            persistedAt = row.get("persisted_at", Instant::class.java)!!,
        )
    }
}
