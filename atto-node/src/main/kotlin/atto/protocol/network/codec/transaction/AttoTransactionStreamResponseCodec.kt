package atto.protocol.network.codec.transaction

import atto.protocol.network.AttoMessageType
import atto.protocol.network.codec.AttoMessageCodec
import atto.protocol.transaction.AttoTransactionStreamResponse
import cash.atto.commons.AttoByteBuffer
import cash.atto.commons.AttoNetwork

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