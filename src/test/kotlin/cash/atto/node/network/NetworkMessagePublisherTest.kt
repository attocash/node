package cash.atto.node.network

import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toAttoHeight
import cash.atto.node.network.guardian.ExpectedResponses
import cash.atto.protocol.AttoTransactionStreamRequest
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class NetworkMessagePublisherTest {
    @Test
    fun `response credits exist before dispatch can send and immediately receive a response`() {
        // Given
        val now = Instant.parse("2026-11-07T00:00:00Z")
        val expectedResponses = ExpectedResponses(Clock.fixed(now, ZoneOffset.UTC))
        val publicUri = URI("ws://localhost:8080")
        val dispatcher = mockk<ApplicationEventPublisher>()
        every { dispatcher.publishEvent(any<Any>()) } answers {
            // A transport listener can send immediately, before other asynchronous listeners run.
            assertTrue(expectedResponses.consume(publicUri))
        }
        val publisher = NetworkMessagePublisher(dispatcher, expectedResponses)
        val payload = AttoTransactionStreamRequest(AttoPublicKey(ByteArray(32) { 1 }), 1UL.toAttoHeight(), 1UL.toAttoHeight())
        val request = DirectNetworkMessage(publicUri, payload, expectedResponseCount = 1UL, timestamp = now)

        // When
        publisher.publish(request)

        // Then
        assertFalse(expectedResponses.consume(publicUri))
    }
}
