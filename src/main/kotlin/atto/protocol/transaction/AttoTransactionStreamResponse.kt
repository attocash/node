package atto.protocol.transaction

import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType
import cash.atto.commons.AttoByteBuffer
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoTransaction


data class AttoTransactionStreamResponse(val transactions: List<AttoTransaction>) : AttoMessage {
    companion object {
        const val maxCount = 1_000

        fun fromByteBuffer(network: AttoNetwork, byteBuffer: AttoByteBuffer): AttoTransactionStreamResponse? {
            val count = byteBuffer.getUShort().toInt()
            if (count == 0 || count > maxCount) {
                return null
            }

            val transactions = ArrayList<AttoTransaction>(count)
            var i = byteBuffer.getIndex()
            for (j in 0 until count) {
                val transaction = AttoTransaction.fromByteBuffer(network, byteBuffer.slice(i)) ?: return null
                transactions.add(transaction)
                i += transaction.getTotalSize()

            }
            return AttoTransactionStreamResponse(transactions)
        }
    }

    init {
        require(transactions.isNotEmpty() && transactions.size <= maxCount) { "Transaction stream should contains between 1 and 1000 transactions" }
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_STREAM_RESPONSE
    }

    fun toByteBuffer(): AttoByteBuffer {
        val byteBuffers = transactions.map { it.toByteBuffer() }
        return AttoByteBuffer(byteBuffers.sumOf { it.size } + 2)
            .add(transactions.size.toUShort())
            .add(byteBuffers)
    }

}

