package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoSignedVote
import cash.atto.commons.AttoTransaction
import cash.atto.commons.AttoVote
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountRepository
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionRepository
import cash.atto.node.election.ElectionVoter
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnectionManager
import cash.atto.node.transaction.TransactionRepository
import cash.atto.node.vote.Vote
import cash.atto.node.vote.convertion.VoteConverter
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoBootstrapTransactionPush
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoVoteStreamCancel
import cash.atto.protocol.AttoVoteStreamRequest
import cash.atto.protocol.AttoVoteStreamResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class LastDiscovererTest {
    @Test
    fun `capacity prevents a new head election and vote request`() =
        runTest {
            // Given
            val fixture = fixture(initiallyAtCapacity = true)
            val transaction = transaction(1)
            val response = fixture.voteResponse(transaction)

            try {
                // When
                fixture.discoverer.processPush(fixture.push(transaction))
                fixture.discoverer.processVoteResponse(response)

                // Then
                assertTrue(fixture.messages.none { it.payload is AttoVoteStreamRequest })
                coVerify(exactly = 0) {
                    fixture.discoveryQueue.queue(any(), DiscoverySource.HEAD)
                }
            } finally {
                fixture.discoverer.close()
            }
        }

    @Test
    fun `consensus at capacity retains the head election when admission is cancelled`() =
        runTest {
            // Given
            val fixture = fixture(initiallyAtCapacity = false)
            val transaction = transaction(2)
            val response = fixture.voteResponse(transaction)
            fixture.discoverer.processPush(fixture.push(transaction))
            fixture.atCapacity.set(true)
            coEvery {
                fixture.discoveryQueue.queue(any(), DiscoverySource.HEAD)
            } coAnswers {
                awaitCancellation()
            }

            try {
                // When
                val admission =
                    launch {
                        fixture.discoverer.processVoteResponse(response)
                    }
                runCurrent()

                // Then
                assertFalse(admission.isCompleted)
                assertTrue(fixture.atCapacity.get())
                coVerify(exactly = 1) {
                    fixture.discoveryQueue.queue(
                        match { it.transaction.hash == transaction.hash },
                        DiscoverySource.HEAD,
                    )
                }
                assertEquals(0, fixture.messages.count { it.payload is AttoVoteStreamCancel })

                // When
                admission.cancelAndJoin()
                coEvery {
                    fixture.discoveryQueue.queue(any(), DiscoverySource.HEAD)
                } returns true
                fixture.discoverer.processVoteResponse(response)

                // Then
                coVerify(exactly = 2) {
                    fixture.discoveryQueue.queue(
                        match { it.transaction.hash == transaction.hash },
                        DiscoverySource.HEAD,
                    )
                }
                assertEquals(1, fixture.messages.count { it.payload is AttoVoteStreamCancel })
            } finally {
                fixture.discoverer.close()
            }
        }

    private fun fixture(initiallyAtCapacity: Boolean): Fixture {
        val atCapacity = AtomicBoolean(initiallyAtCapacity)
        val discoveryQueue = mockk<DiscoveryQueue>()
        every { discoveryQueue.isAtCapacity() } answers { atCapacity.get() }
        coEvery { discoveryQueue.queue(any(), DiscoverySource.HEAD) } returns true

        val voteWeighter = mockk<VoteWeighter>()
        every { voteWeighter.getMinimalConfirmationWeight() } returns ElectionVoter.MIN_WEIGHT
        every { voteWeighter.getMinimalToStaleWeight() } returns ElectionVoter.MIN_WEIGHT

        val voteConverter = mockk<VoteConverter>()
        val messages = mutableListOf<NetworkMessage<*>>()
        val networkMessagePublisher = mockk<NetworkMessagePublisher>()
        every { networkMessagePublisher.publish(any()) } answers {
            messages += firstArg<NetworkMessage<*>>()
        }

        val accountRepository = mockk<AccountRepository>()
        coEvery { accountRepository.findById(any()) } returns null

        val discoverer =
            LastDiscoverer(
                thisNode = mockk<AttoNode>(relaxed = true),
                accountRepository = accountRepository,
                transactionRepository = mockk<TransactionRepository>(),
                uncheckedTransactionRepository = mockk<UncheckedTransactionRepository>(),
                nodeConnectionManager = mockk<NodeConnectionManager>(),
                networkMessagePublisher = networkMessagePublisher,
                eventPublisher = mockk<EventPublisher>(relaxed = true),
                discoveryQueue = discoveryQueue,
                voteConverter = voteConverter,
                voteWeighter = voteWeighter,
            )

        return Fixture(
            discoverer = discoverer,
            discoveryQueue = discoveryQueue,
            voteConverter = voteConverter,
            messages = messages,
            atCapacity = atCapacity,
        )
    }

    private data class Fixture(
        val discoverer: LastDiscoverer,
        val discoveryQueue: DiscoveryQueue,
        val voteConverter: VoteConverter,
        val messages: List<NetworkMessage<*>>,
        val atCapacity: AtomicBoolean,
    ) {
        private val publicUri = URI("ws://127.0.0.1:8080")
        private val socketAddress = InetSocketAddress("127.0.0.1", 8080)

        fun push(transaction: AttoTransaction): InboundNetworkMessage<AttoBootstrapTransactionPush> =
            InboundNetworkMessage(
                source = MessageSource.WEBSOCKET,
                publicUri = publicUri,
                socketAddress = socketAddress,
                payload = AttoBootstrapTransactionPush(transaction),
            )

        fun voteResponse(transaction: AttoTransaction): InboundNetworkMessage<AttoVoteStreamResponse> {
            val attoVote =
                AttoVote(
                    version = 0U.toAttoVersion(),
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(ByteArray(32) { 10 }),
                    blockAlgorithm = AttoAlgorithm.V1,
                    blockHash = transaction.hash,
                    timestamp = AttoVote.finalTimestamp,
                )
            val signedVote =
                AttoSignedVote(
                    vote = attoVote,
                    signature = AttoSignature(ByteArray(64) { 11 }),
                )
            every { voteConverter.convert(signedVote) } returns
                Vote.from(ElectionVoter.MIN_WEIGHT, signedVote)

            return InboundNetworkMessage(
                source = MessageSource.WEBSOCKET,
                publicUri = publicUri,
                socketAddress = socketAddress,
                payload = AttoVoteStreamResponse(signedVote),
            )
        }
    }

    private fun transaction(marker: Byte): AttoTransaction =
        AttoTransaction(
            block =
                AttoReceiveBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(ByteArray(32) { marker }),
                    height = 2U.toAttoHeight(),
                    balance = AttoAmount.MAX,
                    timestamp = AttoInstant.now(),
                    previous = AttoHash(ByteArray(32) { (marker + 1).toByte() }),
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = AttoHash(ByteArray(32) { (marker + 2).toByte() }),
                ),
            signature = AttoSignature(ByteArray(64) { (marker + 3).toByte() }),
            work = AttoWork(ByteArray(8) { (marker + 4).toByte() }),
        )
}
