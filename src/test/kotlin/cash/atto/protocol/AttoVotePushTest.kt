package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import org.junit.jupiter.api.Test

internal class AttoVotePushTest {
    @Test
    fun `should accept valid vote push at p2p ingress`() {
        val message = AttoVotePush(signedVote())

        assertAcceptedAtP2PIngress(message, AttoNetwork.LOCAL)
    }

    @Test
    fun `should reject forged vote push at p2p ingress`() {
        val message = AttoVotePush(forgedVote())

        assertRejectedAtP2PIngress(message, AttoNetwork.LOCAL)
    }
}
