package cash.atto.node.network.guardian

import cash.atto.node.EventPublisher
import cash.atto.node.network.NodeBanned
import cash.atto.node.network.NodeUnbanned
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.time.Clock
import java.time.Instant

class PeerBansTest {
    private var now = Instant.parse("2026-11-07T00:00:00Z")
    private val clock = mockk<Clock> { every { instant() } answers { now } }
    private val publisher = mockk<EventPublisher>(relaxed = true)
    private val bans = PeerBans(publisher, clock)
    private val address = InetAddress.getLoopbackAddress()

    @Test
    fun `ban and expiry change authoritative state before notification`() {
        // Given
        every { publisher.publish(any()) } answers {
            when (val event = firstArg<Any>()) {
                is NodeBanned -> assertTrue(bans.isBanned(event.address))
                is NodeUnbanned -> assertFalse(bans.isBanned(event.address))
                else -> error("Unexpected event $event")
            }
        }

        // When
        bans.ban(address)

        // Then
        assertTrue(bans.isBanned(address))
        assertEquals(setOf(address), bans.snapshot())
        verify(exactly = 1) { publisher.publish(NodeBanned(address, now)) }

        // When
        now = now.plusSeconds(3_599)
        bans.review()

        // Then
        assertTrue(bans.isBanned(address))
        verify(exactly = 0) { publisher.publish(ofType<NodeUnbanned>()) }

        // When
        now = now.plusSeconds(1)
        bans.review()
        bans.review()

        // Then
        assertFalse(bans.isBanned(address))
        assertTrue(bans.snapshot().isEmpty())
        verify(exactly = 1) { publisher.publish(NodeUnbanned(address, now)) }
    }

    @Test
    fun `renewal owns a full hour and snapshots remain detached`() {
        // Given
        bans.ban(address)
        val originalSnapshot = bans.snapshot()
        now = now.plusSeconds(1_800)
        bans.ban(address)

        // When
        now = now.plusSeconds(1_800)
        bans.review()

        // Then
        assertTrue(bans.isBanned(address))
        verify(exactly = 0) { publisher.publish(ofType<NodeUnbanned>()) }

        // When
        now = now.plusSeconds(1_800)
        bans.review()

        // Then
        assertFalse(bans.isBanned(address))
        assertEquals(setOf(address), originalSnapshot)
        verify(exactly = 1) { publisher.publish(NodeUnbanned(address, now)) }
    }
}
