package cash.atto.node.network

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

interface PeerWebSocketSession {
    val incoming: Flow<ByteArray>

    suspend fun send(message: ByteArray)

    suspend fun close(reason: CloseReason? = null)

    fun cancel()
}

internal class KtorPeerWebSocketSession(
    private val session: WebSocketSession,
) : PeerWebSocketSession {
    override val incoming: Flow<ByteArray> =
        session
            .incoming
            .consumeAsFlow()
            .filterIsInstance<Frame.Binary>()
            .map { it.readBytes() }

    override suspend fun send(message: ByteArray) {
        session.outgoing.send(Frame.Binary(true, message))
    }

    override suspend fun close(reason: CloseReason?) {
        if (reason == null) {
            session.close()
        } else {
            session.close(reason)
        }
    }

    override fun cancel() {
        session.cancel()
    }
}
