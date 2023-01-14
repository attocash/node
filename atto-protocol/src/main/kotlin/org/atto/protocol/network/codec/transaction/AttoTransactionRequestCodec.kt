package org.atto.protocol.network.codec.transaction

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.network.AttoMessageType
import org.atto.protocol.network.codec.AttoMessageCodec
import org.atto.protocol.transaction.AttoTransactionRequest

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
            hash = byteBuffer.getHash()
        )
    }

    override fun toByteBuffer(t: AttoTransactionRequest): AttoByteBuffer {
        return AttoByteBuffer(32).add(t.hash)
    }
}