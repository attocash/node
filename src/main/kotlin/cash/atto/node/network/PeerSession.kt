package cash.atto.node.network

import kotlinx.coroutines.flow.Flow

/** A single peer connection. Transport adapters own framing, bounded queues and socket cleanup. */
interface PeerSession {
    companion object {
        const val MAX_MESSAGE_SIZE = 300
        const val QUEUE_CAPACITY = 1_000
    }

    /** Complete binary messages, consumed once by the connection owner. */
    val incoming: Flow<ByteArray>

    /** Enqueue a binary message in send order; completion does not acknowledge peer delivery. */
    suspend fun send(message: ByteArray)

    /** Initiate graceful shutdown without waiting for the remote close acknowledgement. */
    suspend fun close()

    fun cancel()
}
