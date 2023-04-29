package atto.protocol

import atto.commons.AttoByteBuffer
import atto.protocol.network.AttoMessageType

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