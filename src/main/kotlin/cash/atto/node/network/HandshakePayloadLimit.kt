package cash.atto.node.network

import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.decodeFromString
import java.nio.charset.StandardCharsets

internal const val MAX_PUBLIC_URI_SIZE_BYTES = 266
internal const val MAX_COUNTER_CHALLENGE_RESPONSE_SIZE_BYTES = 2_400
internal const val MAX_CHALLENGE_RESPONSE_SIZE_BYTES = 700

internal class HandshakePayloadTooLargeException(
    maxSize: Int,
) : RuntimeException("Handshake payload exceeds $maxSize bytes")

internal suspend inline fun <reified T> ByteReadChannel.receiveHandshakePayload(
    maxSize: Int,
    declaredSize: Long?,
): T {
    if (declaredSize != null && declaredSize > maxSize) {
        val exception = HandshakePayloadTooLargeException(maxSize)
        cancel(exception)
        throw exception
    }

    return DefaultJson.decodeFromString(readHandshakePayload(maxSize))
}

internal suspend fun ByteReadChannel.readHandshakePayload(maxSize: Int): String {
    val payload = ByteArray(maxSize + 1)
    var size = 0

    while (size < payload.size) {
        val read = readAvailable(payload, size, payload.size - size)
        if (read == -1) {
            break
        }
        size += read
    }

    if (size > maxSize) {
        val exception = HandshakePayloadTooLargeException(maxSize)
        cancel(exception)
        throw exception
    }

    return String(payload, 0, size, StandardCharsets.UTF_8)
}
