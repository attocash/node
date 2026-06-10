package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import org.junit.jupiter.api.Test

internal class AttoTransactionPushTest {
    @Test
    fun `should accept valid transaction push at p2p ingress`() {
        val message = AttoTransactionPush(localTransaction())

        assertAcceptedAtP2PIngress(message, AttoNetwork.LOCAL)
    }

    @Test
    fun `should reject transaction push for another network at p2p ingress`() {
        val message = AttoTransactionPush(localTransaction())

        assertRejectedAtP2PIngress(message, AttoNetwork.LIVE)
    }
}
