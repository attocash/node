package cash.atto.node.network

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withTimeoutOrNull
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.netty.Connection
import reactor.netty.http.websocket.WebsocketInbound
import reactor.netty.http.websocket.WebsocketOutbound
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** Owns both bounded handoff queues and the single ordered writer for one connection. */
internal class ReactorPeerSession(
    private val connection: Connection,
    private val inbound: WebsocketInbound,
    private val outbound: WebsocketOutbound,
) : PeerSession {
    init {
        // Install framing during upgrade, before application dispatch can queue raw fragments.
        inbound.aggregateFrames(PeerSession.MAX_MESSAGE_SIZE)
    }

    private class IncomingMessage(
        val binary: ByteArray?,
    )

    private val incomingMessages = Channel<IncomingMessage>(PeerSession.QUEUE_CAPACITY)

    private sealed interface OutgoingMessage {
        class Binary(
            val bytes: ByteArray,
        ) : OutgoingMessage

        data object Close : OutgoingMessage
    }

    private val outgoingMessages = Channel<OutgoingMessage>(PeerSession.QUEUE_CAPACITY)
    private val sendLock = Any()
    private var closing = false
    private val closeRequested = CompletableDeferred<Unit>()
    private val closeFlushed = CompletableDeferred<Unit>()
    private val collected = AtomicBoolean()

    override val incoming: Flow<ByteArray> =
        flow {
            check(collected.compareAndSet(false, true)) { "Peer messages support one collector" }
            for (message in incomingMessages) {
                message.binary?.let { emit(it) }
            }
        }

    override suspend fun send(message: ByteArray) {
        val accepted =
            synchronized(sendLock) {
                !closing && outgoingMessages.trySend(OutgoingMessage.Binary(message.copyOf())).isSuccess
            }
        if (!accepted) {
            cancel()
            throw CancellationException("Peer outgoing queue is full or closed")
        }
    }

    override suspend fun close() {
        val accepted =
            synchronized(sendLock) {
                if (closing) {
                    true
                } else {
                    closing = true
                    outgoingMessages.trySend(OutgoingMessage.Close).isSuccess
                }
            }
        if (!accepted) {
            cancel()
            throw CancellationException("Peer outgoing queue is full or closed")
        }
        closeRequested.complete(Unit)
    }

    override fun cancel() {
        synchronized(sendLock) { closing = true }
        closeFlushed.completeExceptionally(CancellationException("Peer connection closed"))
        incomingMessages.cancel()
        outgoingMessages.cancel()
        connection.dispose()
    }

    suspend fun run(block: suspend (PeerSession) -> Unit) =
        coroutineScope {
            val closeTimeout =
                launch {
                    closeRequested.await()
                    val flushed =
                        withTimeoutOrNull(5.seconds) {
                            closeFlushed.await()
                            true
                        }
                    if (flushed != true) {
                        this@ReactorPeerSession.cancel()
                    }
                }
            val receiver = receiveMessages(this)
            val writer = launch { writeMessages() }
            try {
                block(this@ReactorPeerSession)
            } finally {
                try {
                    if (currentCoroutineContext().isActive) {
                        close()
                        withTimeoutOrNull(5.seconds) { closeFlushed.await() }
                    }
                } finally {
                    receiver.dispose()
                    closeTimeout.cancel()
                    writer.cancel()
                    this@ReactorPeerSession.cancel()
                }
            }
        }

    private fun receiveMessages(sessionScope: CoroutineScope): Disposable =
        inbound
            .receiveFrames()
            .subscribe(
                { frame ->
                    if (frame is BinaryWebSocketFrame || frame is TextWebSocketFrame) {
                        val message =
                            IncomingMessage(
                                if (frame is BinaryWebSocketFrame) {
                                    ByteArray(frame.content().readableBytes()).also {
                                        frame.content().getBytes(frame.content().readerIndex(), it)
                                    }
                                } else {
                                    null
                                },
                            )
                        // Text occupies the same bounded queue until the collector discards it.
                        if (incomingMessages.trySend(message).isFailure) {
                            cancel()
                            sessionScope.cancel("Peer incoming queue is full or closed")
                        }
                    }
                },
                { error ->
                    incomingMessages.close(error)
                    sessionScope.cancel("Peer receive failed", error)
                },
                {
                    incomingMessages.close()
                },
            )

    private suspend fun writeMessages() {
        for (message in outgoingMessages) {
            when (message) {
                is OutgoingMessage.Binary -> {
                    outbound.send(Mono.fromSupplier { Unpooled.wrappedBuffer(message.bytes) }).then().awaitSingleOrNull()
                }

                OutgoingMessage.Close -> {
                    try {
                        outbound.sendClose(1000, "").awaitSingleOrNull()
                    } catch (_: Exception) {
                        // A remote close can race our final close frame after its messages were drained.
                        connection.dispose()
                    } finally {
                        closeFlushed.complete(Unit)
                    }
                    break
                }
            }
        }
    }
}
