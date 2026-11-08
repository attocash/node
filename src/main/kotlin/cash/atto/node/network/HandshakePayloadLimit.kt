package cash.atto.node.network

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux

internal const val MAX_PUBLIC_URI_SIZE_BYTES = 266
internal const val MAX_COUNTER_CHALLENGE_RESPONSE_SIZE_BYTES = 2_400
internal const val MAX_CHALLENGE_RESPONSE_SIZE_BYTES = 700

internal class HandshakePayloadTooLargeException(
    maxSize: Int,
) : RuntimeException("Handshake payload exceeds $maxSize bytes")

// Preserve the JSON settings used by peers before the transport migration.
internal val peerJson =
    Json {
        encodeDefaults = true
        isLenient = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
    }

internal suspend inline fun <reified T> ByteBufFlux.receiveHandshakePayload(
    maxSize: Int,
    declaredSize: Long?,
): T = peerJson.decodeFromString(readHandshakePayload(maxSize, declaredSize).awaitSingle())

internal fun ByteBufFlux.readHandshakePayload(
    maxSize: Int,
    declaredSize: Long?,
): Mono<String> =
    Mono.defer {
        if (declaredSize != null && declaredSize > maxSize) {
            return@defer Mono.error(HandshakePayloadTooLargeException(maxSize))
        }
        val payload = ByteArray(maxSize + 1)
        var size = 0
        handle<Unit> { buffer, sink ->
            val count = minOf(buffer.readableBytes(), payload.size - size)
            buffer.getBytes(buffer.readerIndex(), payload, size, count)
            size += count
            if (size > maxSize) sink.error(HandshakePayloadTooLargeException(maxSize))
        }.then(Mono.fromCallable { payload.decodeToString(0, size) })
    }
