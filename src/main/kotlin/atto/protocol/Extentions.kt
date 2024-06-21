package atto.protocol

import cash.atto.commons.AttoByteBuffer

fun AttoByteBuffer.add(messageType: AttoMessageType): AttoByteBuffer {
    add(messageType.code)
    return this
}

fun AttoByteBuffer.getMessageType(): AttoMessageType = getMessageType(getIndex())

fun AttoByteBuffer.getMessageType(index: Int): AttoMessageType = AttoMessageType.fromCode(getUByte(index))
