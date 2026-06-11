package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toPublicKey
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun `should keep inclusive count valid at maximum`() {
        val message = request(1U, AttoTransactionStreamRequest.MAX_TRANSACTIONS)

        assertTrue(message.isValid(AttoNetwork.LOCAL))
    }

    private fun request(
        startHeight: UInt,
        endHeight: ULong,
    ): AttoTransactionStreamRequest =
        AttoTransactionStreamRequest(
            publicKey = AttoPrivateKey.generate().toPublicKey(),
            startHeight = startHeight.toAttoHeight(),
            endHeight = endHeight.toAttoHeight(),
        )

    private fun request(
        startHeight: UInt,
        endHeight: UInt,
    ): AttoTransactionStreamRequest = request(startHeight, endHeight.toULong())
}
