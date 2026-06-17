package cash.atto.protocol

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoNetwork
import org.junit.jupiter.api.Test

internal class AttoVoteRequestTest {
    @Test
    fun `should accept valid vote request at p2p ingress`() {
        val message = AttoVoteRequest(AttoAlgorithm.V1, validHash())

        assertAcceptedAtP2PIngress(message, AttoNetwork.LOCAL)
    }

    @Test
    fun `should reject invalid vote request at p2p ingress`() {
        val message = AttoVoteRequest(AttoAlgorithm.V1, invalidHash())

        assertRejectedAtP2PIngress(message, AttoNetwork.LOCAL)
    }
}
