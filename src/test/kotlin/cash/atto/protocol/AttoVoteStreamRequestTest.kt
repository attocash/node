package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import org.junit.jupiter.api.Test

internal class AttoVoteStreamRequestTest {
    @Test
    fun `should accept valid vote stream request at p2p ingress`() {
        val message = AttoVoteStreamRequest(validHash())

        assertAcceptedAtP2PIngress(message, AttoNetwork.LOCAL)
    }

    @Test
    fun `should reject invalid vote stream request at p2p ingress`() {
        val message = AttoVoteStreamRequest(invalidHash())

        assertRejectedAtP2PIngress(message, AttoNetwork.LOCAL)
    }
}
