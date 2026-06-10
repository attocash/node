package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import org.junit.jupiter.api.Test

internal class AttoBootstrapTransactionPushTest {
    @Test
    fun `should accept valid bootstrap transaction push at p2p ingress`() {
        val message = AttoBootstrapTransactionPush(localTransaction())

        assertAcceptedAtP2PIngress(message, AttoNetwork.LOCAL)
    }

    @Test
    fun `should reject bootstrap transaction push for another network at p2p ingress`() {
        val message = AttoBootstrapTransactionPush(localTransaction())

        assertRejectedAtP2PIngress(message, AttoNetwork.LIVE)
    }
}
