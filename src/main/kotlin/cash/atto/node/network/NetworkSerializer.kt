@file:OptIn(ExperimentalSerializationApi::class)

package cash.atto.node.network

import cash.atto.commons.AttoNetwork
import cash.atto.commons.toHex
import cash.atto.protocol.AttoMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

@OptIn(ExperimentalSerializationApi::class)
object NetworkSerializer {
    private val logger = KotlinLogging.logger {}

    inline fun <reified T : AttoMessage> serialize(message: T): ByteArray = ProtoBuf.encodeToByteArray(AttoMessage.serializer(), message)

    fun deserialize(
        serialized: ByteArray,
        network: AttoNetwork,
    ): AttoMessage? =
        try {
            val message = ProtoBuf.decodeFromByteArray(AttoMessage.serializer(), serialized)
            if (!message.isValid(network)) {
                logger.trace { "Invalid message $message ${serialized.toHex()}" }
                null
            } else {
                message
            }
        } catch (e: Exception) {
            logger.trace(e) { "Invalid message ${serialized.toHex()}" }
            null
        }
}
