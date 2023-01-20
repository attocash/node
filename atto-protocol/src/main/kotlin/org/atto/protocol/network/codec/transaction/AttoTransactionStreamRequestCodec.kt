package org.atto.protocol.network.codec.transaction

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.AttoMessageType
import org.atto.protocol.network.codec.AttoMessageCodec
import org.atto.protocol.transaction.AttoTransactionStreamRequest

class AttoTransactionStreamRequestCodec : AttoMessageCodec<AttoTransactionStreamRequest> {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_STREAM_REQUEST
    }

    override fun targetClass(): Class<AttoTransactionStreamRequest> {
        return AttoTransactionStreamRequest::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoTransactionStreamRequest? {
        return AttoTransactionStreamRequest.fromByteBuffer(byteBuffer)
    }

    override fun toByteBuffer(t: AttoTransactionStreamRequest): AttoByteBuffer {
        return t.toByteBuffer()
    }
}