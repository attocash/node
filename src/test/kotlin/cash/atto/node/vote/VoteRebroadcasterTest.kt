package cash.atto.node.vote

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.toAttoVersion
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.Transaction
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant

class VoteRebroadcasterTest {
    @Test
    fun `validated vote is not retained when node cannot rebroadcast`() =
        runTest {
            // Given
            val node =
                AttoNode(
                    network = AttoNetwork.LOCAL,
                    protocolVersion = 0U.toUShort(),
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(ByteArray(32)),
                    publicUri = URI("ws://localhost:8080"),
                    features = setOf(NodeFeature.VOTING),
                )
            val voteWeighter = mockk<VoteWeighter>()
            every { voteWeighter.isAboveMinimalRebroadcastWeight(node.publicKey) } returnsMany listOf(false, true)
            val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val rebroadcaster = VoteRebroadcaster(node, voteWeighter, messagePublisher)
            val vote =
                Vote(
                    hash = AttoHash(ByteArray(32)),
                    version = 0U.toAttoVersion(),
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(ByteArray(32)),
                    blockAlgorithm = AttoAlgorithm.V1,
                    blockHash = AttoHash(ByteArray(32)),
                    timestamp = Instant.now(),
                    signature = AttoSignature(ByteArray(64)),
                    weight = AttoAmount(1UL),
                )
            val validated = VoteValidated(mockk<Transaction>(), vote)

            // When
            rebroadcaster.process(VoteReceived(URI("ws://peer:8080"), vote))
            rebroadcaster.process(validated)
            rebroadcaster.process(validated)
            rebroadcaster.process()

            // Then
            verify(exactly = 0) { messagePublisher.publish(any()) }
        }
}
