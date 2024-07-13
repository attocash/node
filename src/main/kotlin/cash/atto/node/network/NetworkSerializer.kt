@file:OptIn(ExperimentalSerializationApi::class)

package cash.atto.node.network

import cash.atto.commons.toBuffer
import cash.atto.commons.toHex
import cash.atto.protocol.AttoMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

@OptIn(ExperimentalSerializationApi::class)
object NetworkSerializer {
    private val logger = KotlinLogging.logger {}

    inline fun <reified T : AttoMessage> serialize(message: T): Buffer =
        ProtoBuf.encodeToByteArray(AttoMessage.serializer(), message).toBuffer()

    fun deserialize(buffer: Buffer): AttoMessage? =
        try {
            ProtoBuf.decodeFromByteArray(AttoMessage.serializer(), buffer.readByteArray())
        } catch (e: Exception) {
            logger.trace(e) { "Invalid message ${buffer.toHex()}" }
            null
        }
}
