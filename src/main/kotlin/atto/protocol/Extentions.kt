package atto.protocol

import atto.protocol.network.AttoMessageType
import cash.atto.commons.AttoByteBuffer

fun AttoByteBuffer.add(messageType: AttoMessageType): AttoByteBuffer {
    add(messageType.code)
    return this
}

fun AttoByteBuffer.getMessageType(): AttoMessageType {
    return getMessageType(getIndex())
}

fun AttoByteBuffer.getMessageType(index: Int): AttoMessageType {
    return AttoMessageType.fromCode(getUByte(index))
}