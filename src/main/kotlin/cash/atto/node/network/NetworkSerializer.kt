@file:OptIn(ExperimentalSerializationApi::class)

package cash.atto.node.network

import cash.atto.commons.AttoByteBuffer
import cash.atto.commons.serialiazers.protobuf.AttoProtobuf
import cash.atto.protocol.AttoMessage
import cash.atto.protocol.AttoMessageType
import cash.atto.protocol.messageSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import mu.KotlinLogging

object NetworkSerializer

inline fun <reified T : AttoMessage> NetworkSerializer.serialize(message: T): AttoByteBuffer {
    val messageType = message.messageType()
    val serializer = messageType.messageSerializer<T>()
    val byteArray = AttoProtobuf.encodeToByteArray(serializer, message)
    return AttoByteBuffer(byteArray.size + 1)
        .add(messageType.code)
        .add(byteArray)
}

private val logger = KotlinLogging.logger {}

fun NetworkSerializer.deserialize(byteBuffer: AttoByteBuffer): AttoMessage? {
    val messageType = AttoMessageType.fromCode(byteBuffer.getUByte())
    val serializer = messageType.messageSerializer<AttoMessage>()
    return try {
        val byteArray = byteBuffer.getByteArray(1, byteBuffer.size - 1)
        AttoProtobuf.decodeFromByteArray(serializer, byteArray)
    } catch (e: Exception) {
        logger.trace(e) { "Invalid message ${byteBuffer.toHex()}" }
        null
    }
}
