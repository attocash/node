package org.atto.protocol.transaction

import org.atto.commons.AttoByteBuffer
import org.atto.commons.AttoPublicKey
import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.AttoMessageType


/**
 *
 */
data class AttoTransactionStreamRequest(
    val publicKey: AttoPublicKey,
    val startHeight: ULong,
    val endHeight: ULong
) : AttoMessage {

    companion object {
        val size = 48

        fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoTransactionStreamRequest? {
            if (size > byteBuffer.size) {
                return null
            }
            return AttoTransactionStreamRequest(
                byteBuffer.getPublicKey(),
                byteBuffer.getULong(),
                byteBuffer.getULong()
            )
        }
    }

    init {
        val count = endHeight - startHeight
        require(count <= 1000UL && count > 0UL) { "Transaction stream should contains between 1 and 1000 transactions" }
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_STREAM_REQUEST
    }

    fun toByteBuffer(): AttoByteBuffer {
        return AttoByteBuffer(size)
            .add(publicKey)
            .add(startHeight)
            .add(endHeight)
    }

}

