package cash.atto.node.convertion

import cash.atto.commons.AttoTransaction
import cash.atto.commons.toBigInteger
import cash.atto.commons.toBuffer
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.toTransaction
import io.r2dbc.spi.Row
import org.springframework.core.convert.converter.Converter
import org.springframework.data.r2dbc.mapping.OutboundRow
import org.springframework.r2dbc.core.Parameter
import java.time.Instant

class TransactionSerializerDBConverter : Converter<Transaction, OutboundRow> {
    override fun convert(transaction: Transaction): OutboundRow {
        val block = transaction.block

        val row = OutboundRow()
        with(row) {
            put("hash", Parameter.from(block.hash))
            put("public_key", Parameter.from(block.publicKey))
            put("height", Parameter.from(block.height.value.toBigInteger()))
            put("serialized", Parameter.from(transaction.toAttoTransaction().toBuffer()))
            put("received_at", Parameter.from(transaction.receivedAt))
            put("persisted_at", Parameter.fromOrEmpty(transaction.persistedAt, Instant::class.java))
        }

        return row
    }
}

class TransactionDeserializerDBConverter : Converter<Row, Transaction> {
    override fun convert(row: Row): Transaction {
        val serializedBlock = row.get("serialized", ByteArray::class.java)!!.toBuffer()
        return AttoTransaction.fromBuffer(serializedBlock)!!.toTransaction()
    }
}
