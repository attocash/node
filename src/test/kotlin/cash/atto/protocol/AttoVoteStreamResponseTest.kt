package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoVote
import org.junit.jupiter.api.Test

internal class AttoVoteStreamResponseTest {
    @Test
    fun `should accept valid final vote stream response at p2p ingress`() {
        val message = AttoVoteStreamResponse(signedVote(AttoVote.finalTimestamp))

        assertAcceptedAtP2PIngress(message, AttoNetwork.LOCAL)
    }

    @Test
    fun `should reject non-final vote stream response at p2p ingress`() {
        val message = AttoVoteStreamResponse(signedVote())

        assertRejectedAtP2PIngress(message, AttoNetwork.LOCAL)
    }
}
