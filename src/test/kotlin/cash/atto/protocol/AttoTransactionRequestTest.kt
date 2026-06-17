package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import org.junit.jupiter.api.Test

internal class AttoTransactionRequestTest {
    @Test
    fun `should accept valid transaction request at p2p ingress`() {
        val message = AttoTransactionRequest(validHash())

        assertAcceptedAtP2PIngress(message, AttoNetwork.LOCAL)
    }

    @Test
    fun `should reject invalid transaction request at p2p ingress`() {
        val message = AttoTransactionRequest(invalidHash())

        assertRejectedAtP2PIngress(message, AttoNetwork.LOCAL)
    }
}
