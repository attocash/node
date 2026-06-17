package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import org.junit.jupiter.api.Test

internal class AttoTransactionResponseTest {
    @Test
    fun `should accept valid transaction response at p2p ingress`() {
        val message = AttoTransactionResponse(localTransaction())

        assertAcceptedAtP2PIngress(message, AttoNetwork.LOCAL)
    }

    @Test
    fun `should reject transaction response for another network at p2p ingress`() {
        val message = AttoTransactionResponse(localTransaction())

        assertRejectedAtP2PIngress(message, AttoNetwork.LIVE)
    }
}
