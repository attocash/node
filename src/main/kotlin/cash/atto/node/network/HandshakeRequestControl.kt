package cash.atto.node.network

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Semaphore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal const val MAX_CONCURRENT_HANDSHAKES = 64
internal val HANDSHAKE_REQUEST_TIMEOUT = 5.seconds

internal enum class HandshakeRequestOutcome {
    COMPLETED,
    CAPACITY_REJECTED,
    TIMED_OUT,
}

internal class HandshakeRequestControl(
    maxConcurrentHandshakes: Int = MAX_CONCURRENT_HANDSHAKES,
    private val requestTimeout: Duration = HANDSHAKE_REQUEST_TIMEOUT,
) {
    private val permits = Semaphore(maxConcurrentHandshakes)

    init {
        require(maxConcurrentHandshakes > 0) { "maxConcurrentHandshakes must be positive" }
        require(requestTimeout.isPositive()) { "requestTimeout must be positive" }
    }

    suspend fun execute(
        call: ApplicationCall,
        block: suspend (ByteReadChannel) -> Unit,
    ): HandshakeRequestOutcome {
        val channel = call.receiveChannel()
        if (!permits.tryAcquire()) {
            channel.cancel(null)
            return HandshakeRequestOutcome.CAPACITY_REJECTED
        }

        try {
            val completed =
                withTimeoutOrNull(requestTimeout) {
                    block(channel)
                    true
                } ?: false

            if (completed) {
                return HandshakeRequestOutcome.COMPLETED
            }

            channel.cancel(HandshakeRequestTimeoutException(requestTimeout))
            return HandshakeRequestOutcome.TIMED_OUT
        } finally {
            permits.release()
        }
    }
}

internal class HandshakeRequestTimeoutException(
    timeout: Duration,
) : RuntimeException("Handshake request exceeded $timeout")
