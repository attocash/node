@file:OptIn(ExperimentalSerializationApi::class)

package cash.atto.node.network

import cash.atto.commons.toHex
import cash.atto.protocol.AttoMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

@OptIn(ExperimentalSerializationApi::class)
object NetworkSerializer {
    private val logger = KotlinLogging.logger {}

    inline fun <reified T : AttoMessage> serialize(message: T): ByteArray =
        ProtoBuf.encodeToByteArray(AttoMessage.serializer(), message)

    fun deserialize(serialized: ByteArray): AttoMessage? =
        try {
            ProtoBuf.decodeFromByteArray(AttoMessage.serializer(), serialized)
        } catch (e: Exception) {
            logger.trace(e) { "Invalid message ${serialized.toHex()}" }
            null
        }
}
