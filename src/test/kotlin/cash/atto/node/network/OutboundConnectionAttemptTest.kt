package cash.atto.node.network

import cash.atto.protocol.AttoNode
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutboundConnectionAttemptTest {
    @Test
    fun `authenticated attempt does not cancel connection when timeout fires`() =
        runTest {
            // Given
            val attempt = OutboundConnectionAttempt()
            val node = mockk<AttoNode>()
            val connectionJob = Job()

            // When
            val authenticated = attempt.authenticate(node)
            val expired = attempt.expire(CancellationException("Connection attempt timed out"))
            if (expired) {
                connectionJob.cancel()
            }

            // Then
            assertTrue(authenticated)
            assertFalse(expired)
            assertTrue(connectionJob.isActive)
            assertSame(node, attempt.awaitNode())
        }

    @Test
    fun `expired attempt rejects late authentication`() =
        runTest {
            // Given
            val attempt = OutboundConnectionAttempt()
            val node = mockk<AttoNode>()
            val connectionJob = Job()

            // When
            val expired = attempt.expire(CancellationException("Connection attempt timed out"))
            if (expired) {
                connectionJob.cancel()
            }
            val authenticated = attempt.authenticate(node)

            // Then
            assertTrue(expired)
            assertTrue(connectionJob.isCancelled)
            assertFalse(authenticated)
        }
}
