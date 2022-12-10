package org.atto.node.network.codec

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.AttoNode
import org.atto.protocol.add
import org.atto.protocol.getMessageType
import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.AttoMessageType
import org.atto.protocol.network.codec.AttoMessageCodec
import org.springframework.stereotype.Component

@Component
class MessageCodecManager(val thisNode: AttoNode, codecs: List<AttoMessageCodec<*>>) {
    val codecMap = codecs.associateBy({ it.messageType() }, { it })

    companion object {
        val size = 8
    }


    fun fromByteArray(byteBuffer: AttoByteBuffer): AttoMessage? {
        if (AttoMessage.size >= byteBuffer.size) {
            return null
        }

        val network = byteBuffer.getNetwork()

        if (thisNode.network != network) {
            return null
        }

        val protocolVersion = byteBuffer.getUShort()

        if (thisNode.minProtocolVersion > protocolVersion) {
            return null
        }

        if (thisNode.maxProtocolVersion < protocolVersion) {
            return null
        }

        val type = byteBuffer.getMessageType()

        if (type == AttoMessageType.UNKNOWN) {
            return null
        }

        val size = byteBuffer.getUByte().toInt()

        if (size != byteBuffer.size - 8) {
            return null
        }

        return codecMap[type]!!.fromByteBuffer(byteBuffer.slice(8))
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : AttoMessage> toByteBuffer(t: T): AttoByteBuffer {
        val codec = codecMap[t.messageType()]!! as AttoMessageCodec<T>
        val byteBuffer = codec.toByteBuffer(t)

        return AttoByteBuffer(size + byteBuffer.size)
            .add(thisNode.network)
            .add(thisNode.protocolVersion)
            .add(t.messageType())
            .add(byteBuffer.size.toShort())
            .add(byteBuffer)
    }
}