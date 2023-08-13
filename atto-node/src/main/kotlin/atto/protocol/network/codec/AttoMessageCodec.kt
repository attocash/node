package atto.protocol.network.codec

import atto.protocol.network.AttoMessage
import atto.protocol.network.AttoMessageType

interface AttoMessageCodec<T : AttoMessage> : AttoCodec<T> {

    fun messageType(): AttoMessageType

    fun targetClass(): Class<T>

}