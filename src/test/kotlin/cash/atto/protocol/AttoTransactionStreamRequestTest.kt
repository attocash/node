package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toPublicKeyBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AttoTransactionStreamRequestTest {
    @Test
    fun `should accept valid single transaction stream request at p2p ingress`() {
        val message = request(1U, 1U)

        assertAcceptedAtP2PIngress(message, AttoNetwork.LOCAL)
    }

    @Test
    fun `should accept valid transaction stream request at p2p ingress`() {
        val message = request(1U, 2U)

        assertAcceptedAtP2PIngress(message, AttoNetwork.LOCAL)
    }

    @Test
    fun `should keep inclusive count valid at maximum`() =
        runTest {
            val message = request(1U, AttoTransactionStreamRequest.MAX_TRANSACTIONS)

            assertTrue(message.isValid(AttoNetwork.LOCAL))
        }

    @Test
    fun `should reject inclusive count above maximum`() {
        // Given
        val endHeight = AttoTransactionStreamRequest.MAX_TRANSACTIONS + 1UL

        // When
        val exception =
            assertThrows<IllegalArgumentException> {
                request(1U, endHeight)
            }

        // Then
        assertEquals(
            "The number of transactions must not exceed the maximum limit of 1000. Requested 1001",
            exception.message,
        )
    }

    @Test
    fun `should reject range that previously overflowed the inclusive count`() {
        // Given
        val startHeight = 0U
        val endHeight = ULong.MAX_VALUE

        // When
        val exception =
            assertThrows<IllegalArgumentException> {
                request(startHeight, endHeight)
            }

        // Then
        assertEquals("Start height must be greater than or equal to 1", exception.message)
    }

    private fun request(
        startHeight: UInt,
        endHeight: ULong,
    ): AttoTransactionStreamRequest =
        AttoTransactionStreamRequest(
            publicKey = AttoPrivateKey.generate().toPublicKeyBlocking(),
            startHeight = startHeight.toAttoHeight(),
            endHeight = endHeight.toAttoHeight(),
        )

    private fun request(
        startHeight: UInt,
        endHeight: UInt,
    ): AttoTransactionStreamRequest = request(startHeight, endHeight.toULong())
}
