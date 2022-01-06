package org.atto.protocol.network.codec

import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.MessageType

interface MessageCodec<T : AttoMessage> : Codec<T> {

    fun messageType(): MessageType

    fun targetClass(): Class<T>

}