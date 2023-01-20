package org.atto.protocol.network.codec.transaction

import org.atto.commons.AttoByteBuffer
import org.atto.commons.AttoNetwork
import org.atto.protocol.network.AttoMessageType
import org.atto.protocol.network.codec.AttoMessageCodec
import org.atto.protocol.transaction.AttoTransactionStreamResponse

class AttoTransactionStreamResponseCodec(private val network: AttoNetwork) :
    AttoMessageCodec<AttoTransactionStreamResponse> {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_STREAM_RESPONSE
    }

    override fun targetClass(): Class<AttoTransactionStreamResponse> {
        return AttoTransactionStreamResponse::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoTransactionStreamResponse? {
        return AttoTransactionStreamResponse.fromByteBuffer(network, byteBuffer)
    }

    override fun toByteBuffer(t: AttoTransactionStreamResponse): AttoByteBuffer {
        return t.toByteBuffer()
    }
}