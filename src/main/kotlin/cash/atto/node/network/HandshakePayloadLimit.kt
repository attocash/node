package cash.atto.node.network

import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.netty.buffer.ByteBuf
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import java.nio.charset.StandardCharsets

// Handshake JSON contains fixed-size cryptographic fields and one canonical peer URI.
internal const val MAX_HANDSHAKE_PAYLOAD_SIZE_BYTES = 4 * 1024

internal class HandshakePayloadTooLargeException : RuntimeException("Handshake payload exceeds $MAX_HANDSHAKE_PAYLOAD_SIZE_BYTES bytes")

internal suspend fun ApplicationCall.receiveHandshakePayload(channel: ByteReadChannel): CounterChallengeResponse {
    val declaredSize = request.contentLength()
    if (declaredSize != null && declaredSize > MAX_HANDSHAKE_PAYLOAD_SIZE_BYTES) {
        val exception = HandshakePayloadTooLargeException()
        channel.cancel(exception)
        throw exception
    }

    val payload = channel.readHandshakePayload()
    return DefaultJson.decodeFromString(payload)
}

internal fun ByteBufFlux.receiveHandshakePayload(): Mono<String> =
    collect(
        { HandshakePayloadAccumulator() },
        { accumulator, chunk -> accumulator.append(chunk) },
    ).map { it.decode() }

private class HandshakePayloadAccumulator {
    private val payload = ByteArray(MAX_HANDSHAKE_PAYLOAD_SIZE_BYTES)
    private var size = 0

    fun append(chunk: ByteBuf) {
        val chunkSize = chunk.readableBytes()
        if (chunkSize > payload.size - size) {
            throw HandshakePayloadTooLargeException()
        }

        chunk.getBytes(chunk.readerIndex(), payload, size, chunkSize)
        size += chunkSize
    }

    fun decode(): String = String(payload, 0, size, StandardCharsets.UTF_8)
}

private suspend fun ByteReadChannel.readHandshakePayload(): String {
    val payload = ByteArray(MAX_HANDSHAKE_PAYLOAD_SIZE_BYTES + 1)
    var size = 0

    while (size < payload.size) {
        val read = readAvailable(payload, size, payload.size - size)
        if (read == -1) {
            break
        }
        size += read
    }

    if (size > MAX_HANDSHAKE_PAYLOAD_SIZE_BYTES) {
        val exception = HandshakePayloadTooLargeException()
        cancel(exception)
        throw exception
    }

    return String(payload, 0, size, StandardCharsets.UTF_8)
}
