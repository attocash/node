package cash.atto.node.vote.keeping

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoSignedVote
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoVote
import cash.atto.commons.toAttoVersion
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.MutableConnectedPeers
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.vote.MissingVote
import cash.atto.node.vote.VoteRepository
import cash.atto.node.vote.VoteService
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoVoteRequest
import cash.atto.protocol.AttoVoteResponse
import cash.atto.protocol.NodeFeature
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI

class VoteKeeperTest {
    @Test
    fun `missing vote requires historical representative and uses current URI after reconnect`() =
        runTest {
            // Given
            val representative = node(1).copy(features = setOf(NodeFeature.VOTING))
            val missing = MissingVote(hash(1), representative.publicKey)
            val fixture = fixture(missing)
            fixture.peers.connect(representative)

            // When
            fixture.keeper.keep()

            // Then
            verify(exactly = 0) { fixture.publisher.publish(any()) }

            // When
            fixture.peers.disconnect(representative.publicUri)
            val reconnected =
                representative.copy(
                    publicUri = URI("ws://127.0.0.1:9001"),
                    features = setOf(NodeFeature.HISTORICAL),
                )
            fixture.peers.connect(reconnected)
            fixture.keeper.keep()
            fixture.keeper.keep()

            // Then
            verify(exactly = 1) {
                fixture.publisher.publish(
                    match {
                        it is DirectNetworkMessage<*> &&
                            it.publicUri == reconnected.publicUri &&
                            (it.payload as AttoVoteRequest).blockHash == missing.lastTransactionHash
                    },
                )
            }
        }

    @Test
    fun `departed historical representative cannot receive a new missing vote request`() =
        runTest {
            // Given
            val representative = node(1)
            val fixture = fixture(MissingVote(hash(1), representative.publicKey))
            fixture.peers.connect(representative)
            fixture.peers.disconnect(representative.publicUri)

            // When
            fixture.keeper.keep()

            // Then
            verify(exactly = 0) { fixture.publisher.publish(any()) }
        }

    @Test
    fun `local representative signs and enqueues missing final vote without a peer connection`() =
        runTest {
            // Given
            val thisNode = node(100)
            val missing = MissingVote(hash(1), thisNode.publicKey)
            val fixture = fixture(missing, thisNode)

            // When
            fixture.keeper.keep()

            // Then
            coVerify(exactly = 1) {
                fixture.signer.sign(
                    match<AttoVote> {
                        it.publicKey == thisNode.publicKey &&
                            it.blockHash == missing.lastTransactionHash &&
                            it.timestamp == AttoVote.finalTimestamp
                    },
                )
            }
            verify(exactly = 1) {
                fixture.service.enqueue(
                    match {
                        it.publicKey == thisNode.publicKey && it.blockHash == missing.lastTransactionHash && it.isFinal()
                    },
                )
            }
            verify(exactly = 0) { fixture.publisher.publish(any()) }
        }

    @Test
    fun `pending response remains consumable after representative disconnects`() =
        runTest {
            // Given
            val representative = node(1)
            val missing = MissingVote(hash(1), representative.publicKey)
            val fixture = fixture(missing)
            fixture.peers.connect(representative)
            fixture.keeper.keep()
            val signedVote =
                AttoSignedVote(
                    AttoVote(
                        version = 0U.toAttoVersion(),
                        algorithm = AttoAlgorithm.V1,
                        publicKey = representative.publicKey,
                        blockAlgorithm = AttoAlgorithm.V1,
                        blockHash = missing.lastTransactionHash,
                        timestamp = AttoVote.finalTimestamp,
                    ),
                    AttoSignature(ByteArray(64) { 1 }),
                )
            val response =
                InboundNetworkMessage(
                    MessageSource.WEBSOCKET,
                    representative.publicUri,
                    InetSocketAddress("127.0.0.1", 10_001),
                    AttoVoteResponse(signedVote),
                )
            fixture.peers.disconnect(representative.publicUri)

            // When
            fixture.keeper.process(response)
            fixture.keeper.process(response)

            // Then
            verify(exactly = 1) {
                fixture.service.enqueue(
                    match {
                        it.publicKey == representative.publicKey && it.blockHash == missing.lastTransactionHash
                    },
                )
            }
        }

    private fun fixture(
        missing: MissingVote,
        thisNode: AttoNode = node(100),
    ): Fixture {
        val weight = AttoAmount(1_000UL)
        val voteWeighter = mockk<VoteWeighter>()
        every { voteWeighter.getMinimalConfirmationWeight() } returns weight
        every { voteWeighter.get(any<AttoPublicKey>()) } returns weight
        val repository = mockk<VoteRepository>()
        coEvery { repository.findMissingVote(any()) } returns listOf(missing)
        val service = mockk<VoteService>(relaxed = true)
        val publisher = mockk<NetworkMessagePublisher>(relaxed = true)
        val signer = mockk<AttoSigner>()
        coEvery { signer.sign(any<AttoVote>()) } returns AttoSignature(ByteArray(64) { 1 })
        val peers = MutableConnectedPeers()
        val keeper = VoteKeeper(thisNode, voteWeighter, repository, service, publisher, signer, peers)
        return Fixture(keeper, service, publisher, signer, peers)
    }

    private data class Fixture(
        val keeper: VoteKeeper,
        val service: VoteService,
        val publisher: NetworkMessagePublisher,
        val signer: AttoSigner,
        val peers: MutableConnectedPeers,
    )

    private fun node(marker: Byte): AttoNode =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = AttoNode.CURRENT_PROTOCOL_VERSION,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(ByteArray(32) { marker }),
            publicUri = URI("ws://127.0.0.1:${8_000 + marker}"),
            features = setOf(NodeFeature.HISTORICAL, NodeFeature.VOTING),
        )

    private fun hash(marker: Byte): AttoHash = AttoHash(ByteArray(32) { marker })
}
