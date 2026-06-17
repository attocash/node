package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import org.junit.jupiter.api.Test

internal class AttoVoteStreamCancelTest {
    @Test
    fun `should accept valid vote stream cancel at p2p ingress`() {
        val message = AttoVoteStreamCancel(validHash())

        assertAcceptedAtP2PIngress(message, AttoNetwork.LOCAL)
    }

    @Test
    fun `should reject invalid vote stream cancel at p2p ingress`() {
        val message = AttoVoteStreamCancel(invalidHash())

        assertRejectedAtP2PIngress(message, AttoNetwork.LOCAL)
    }
}
