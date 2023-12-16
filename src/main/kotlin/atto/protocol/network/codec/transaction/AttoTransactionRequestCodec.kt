package atto.protocol.network.codec.transaction

import atto.protocol.network.AttoMessageType
import atto.protocol.network.codec.AttoMessageCodec
import atto.protocol.transaction.AttoTransactionRequest
import cash.atto.commons.AttoByteBuffer

class AttoTransactionRequestCodec : AttoMessageCodec<AttoTransactionRequest> {

    override fun messageType(): AttoMessageType {
        return AttoMessageType.TRANSACTION_REQUEST
    }

    override fun targetClass(): Class<AttoTransactionRequest> {
        return AttoTransactionRequest::class.java
    }

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoTransactionRequest? {
        if (byteBuffer.size < AttoTransactionRequest.size) {
            return null
        }

        return AttoTransactionRequest(
            hash = byteBuffer.getBlockHash()
        )
    }

    override fun toByteBuffer(t: AttoTransactionRequest): AttoByteBuffer {
        return AttoByteBuffer(32).add(t.hash)
    }
}