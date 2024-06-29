@file:OptIn(ExperimentalSerializationApi::class)

package cash.atto.node.network

import cash.atto.commons.serialiazers.protobuf.AttoProtobuf
import cash.atto.commons.toBuffer
import cash.atto.commons.toHex
import cash.atto.protocol.AttoMessage
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import mu.KotlinLogging

@OptIn(ExperimentalSerializationApi::class)
object NetworkSerializer {
    private val logger = KotlinLogging.logger {}

    inline fun <reified T : AttoMessage> serialize(message: T): Buffer =
        AttoProtobuf.encodeToByteArray(AttoMessage.serializer(), message).toBuffer()

    fun deserialize(buffer: Buffer): AttoMessage? =
        try {
            AttoProtobuf.decodeFromByteArray(AttoMessage.serializer(), buffer.readByteArray())
        } catch (e: Exception) {
            logger.trace(e) { "Invalid message ${buffer.toHex()}" }
            null
        }
}
