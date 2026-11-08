package cash.atto.node.network

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.Connection
import reactor.netty.NettyOutbound
import reactor.netty.http.websocket.WebsocketInbound
import reactor.netty.http.websocket.WebsocketOutbound

@OptIn(ExperimentalCoroutinesApi::class)
class ReactorPeerSessionTest {
    @Test
    fun `close returns while writer is blocked and disposes socket after five seconds`() =
        runTest {
            // Given
            val writeStarted = CompletableDeferred<Unit>()
            val closeReturned = CompletableDeferred<Unit>()
            val releaseApplication = CompletableDeferred<Unit>()
            val connection = mockk<Connection>(relaxed = true)
            val inbound = mockk<WebsocketInbound>()
            every { inbound.aggregateFrames(PeerSession.MAX_MESSAGE_SIZE) } returns inbound
            every { inbound.receiveFrames() } returns Flux.never()
            val blockedWrite = mockk<NettyOutbound>()
            every { blockedWrite.then() } returns Mono.never<Void>().doOnSubscribe { writeStarted.complete(Unit) }
            val outbound = mockk<WebsocketOutbound>()
            every { outbound.send(any()) } returns blockedWrite
            every { outbound.sendClose(any(), any()) } returns Mono.empty()
            val session = ReactorPeerSession(connection, inbound, outbound)
            val managed =
                async {
                    runCatching {
                        session.run { peer ->
                            peer.send(byteArrayOf(1))
                            writeStarted.await()
                            peer.close()
                            closeReturned.complete(Unit)
                            releaseApplication.await()
                        }
                    }
                }

            try {
                // When
                runCurrent()

                // Then: graceful close is queued behind the accepted write without blocking its caller.
                assertTrue(writeStarted.isCompleted)
                assertTrue(closeReturned.isCompleted)
                assertFalse(managed.isCompleted)
                assertEquals(0L, testScheduler.currentTime)
                verify(exactly = 0) { outbound.sendClose(any(), any()) }
                verify(exactly = 0) { connection.dispose() }

                // When
                advanceTimeBy(4_999)
                runCurrent()

                // Then
                verify(exactly = 0) { connection.dispose() }

                // When
                advanceTimeBy(1)
                runCurrent()

                // Then
                assertEquals(5_000L, testScheduler.currentTime)
                verify(exactly = 1) { connection.dispose() }
                verify(exactly = 0) { outbound.sendClose(any(), any()) }

                // When
                releaseApplication.complete(Unit)
                runCurrent()

                // Then: inspect the worker outcome so an unexpected failure cannot disappear in a child coroutine.
                assertInstanceOf(CancellationException::class.java, managed.await().exceptionOrNull())
            } finally {
                releaseApplication.complete(Unit)
                managed.cancelAndJoin()
            }
        }
}
