package cash.atto.node.convertion

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoTransaction
import cash.atto.commons.PreviousSupport
import cash.atto.commons.toBuffer
import cash.atto.node.bootstrap.unchecked.UncheckedTransaction
import cash.atto.node.bootstrap.unchecked.toUncheckedTransaction
import cash.atto.node.transaction.toTransaction
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
            put("public_key", Parameter.from(block.publicKey))
            put("height", Parameter.from(block.height.value))
            put(
                "previous",
                Parameter.fromOrEmpty(if (block is PreviousSupport) block.previous else null, AttoHash::class.java),
            )
            put("serialized", Parameter.from(uncheckedTransaction.toTransaction().toAttoTransaction().toBuffer()))
            put("received_at", Parameter.from(uncheckedTransaction.receivedAt))
            put("persisted_at", Parameter.fromOrEmpty(uncheckedTransaction.persistedAt, Instant::class.java))
        }

        return row
    }
}

@Component
class UncheckedTransactionDeserializerDBConverter : DBConverter<Row, UncheckedTransaction> {
    override fun convert(row: Row): UncheckedTransaction {
        val serializedBlock = row.get("serialized", ByteArray::class.java)!!.toBuffer()
        return AttoTransaction.fromBuffer(serializedBlock)!!.toTransaction().toUncheckedTransaction()
    }
}
