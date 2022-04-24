package org.atto.protocol.network.codec

import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.AttoMessageType

interface AttoMessageCodec<T : AttoMessage> : AttoCodec<T> {

    fun messageType(): AttoMessageType

    fun targetClass(): Class<T>

}