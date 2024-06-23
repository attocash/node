@file:OptIn(ExperimentalSerializationApi::class)

package cash.atto.node.network

import cash.atto.commons.AttoByteBuffer
import cash.atto.commons.serialiazers.protobuf.AttoProtobuf
import cash.atto.protocol.AttoMessage
import kotlinx.serialization.ExperimentalSerializationApi
import mu.KotlinLogging

@OptIn(ExperimentalSerializationApi::class)
object NetworkSerializer {
    private val logger = KotlinLogging.logger {}

    inline fun <reified T : AttoMessage> serialize(message: T): AttoByteBuffer =
        AttoProtobuf.encodeToByteArray(AttoMessage.serializer(), message).let {
            AttoByteBuffer(it.size)
                .add(it)
        }

    fun deserialize(byteBuffer: AttoByteBuffer): AttoMessage? =
        try {
            AttoProtobuf.decodeFromByteArray(AttoMessage.serializer(), byteBuffer.toByteArray())
        } catch (e: Exception) {
            logger.trace(e) { "Invalid message ${byteBuffer.toHex()}" }
            null
        }
}
