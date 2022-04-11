package org.atto.node.network.codec

import org.atto.commons.AttoNetwork
import org.atto.commons.toUShort
import org.atto.protocol.Node
import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.MessageType
import org.atto.protocol.network.codec.MessageCodec
import org.springframework.stereotype.Component
import java.nio.ByteBuffer

@Component
class MessageCodecManager(val thisNode: Node, codecs: List<MessageCodec<*>>) {
    val codecMap = codecs.associateBy({ it.messageType() }, { it })

    companion object {
        val size = 8
    }


    fun fromByteArray(byteArray: ByteArray): AttoMessage? {
        if (AttoMessage.size >= byteArray.size) {
            return null
        }

        val network = AttoNetwork.from(byteArray.sliceArray(0 until 3).toString(Charsets.UTF_8))

        if (thisNode.network != network) {
            return null
        }

        val protocolVersion = byteArray.sliceArray(3 until 5).toUShort()

        if (thisNode.minimalProtocolVersion > protocolVersion) {
            return null
        }

        val type = MessageType.fromCode(byteArray.sliceArray(5 until 6)[0].toUByte())

        if (type == MessageType.UNKNOWN) {
            return null
        }

        val size = byteArray.sliceArray(6 until 8).toUShort().toInt()

        if (size != byteArray.size - 8) {
            return null
        }

        val messageByteArray = byteArray.sliceArray(8 until byteArray.size)

        return codecMap[type]!!.fromByteBuffer(messageByteArray)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : AttoMessage> toByteArray(t: T): ByteArray {
        val codec = codecMap[t.messageType()]!! as MessageCodec<T>
        val byteArray = codec.toByteArray(t)

        return ByteBuffer.allocate(size + byteArray.size)
            .put(thisNode.network.environment.toByteArray(Charsets.UTF_8))
            .putShort(thisNode.protocolVersion.toShort())
            .put(t.messageType().code.toByte())
            .putShort(byteArray.size.toShort())
            .put(byteArray)
            .array()
    }
}