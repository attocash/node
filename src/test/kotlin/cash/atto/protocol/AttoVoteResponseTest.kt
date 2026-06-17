package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoVote
import org.junit.jupiter.api.Test

internal class AttoVoteResponseTest {
    @Test
    fun `should accept valid final vote response at p2p ingress`() {
        val message = AttoVoteResponse(signedVote(AttoVote.finalTimestamp))

        assertAcceptedAtP2PIngress(message, AttoNetwork.LOCAL)
    }

    @Test
    fun `should reject non-final vote response at p2p ingress`() {
        val message = AttoVoteResponse(signedVote())

        assertRejectedAtP2PIngress(message, AttoNetwork.LOCAL)
    }
}
