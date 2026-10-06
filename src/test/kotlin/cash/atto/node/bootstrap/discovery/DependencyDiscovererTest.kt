package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoVote
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.node.election.ElectionVoter
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteDropReason
import cash.atto.node.vote.VoteDropped
import cash.atto.node.vote.weight.VoteWeighter
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DependencyDiscovererTest {
    @Test
    fun `final consensus at capacity retains the holder when admission is cancelled`() =
        runTest {
            // Given
            val discoveryQueue = mockk<DiscoveryQueue>()
            every { discoveryQueue.isAtCapacity() } returns true
            coEvery {
                discoveryQueue.queue(any(), DiscoverySource.DEPENDENCY)
            } coAnswers {
                awaitCancellation()
            }
            val transaction = transaction(1)
            val finalVote = finalVote(transaction.hash)
            val voteWeighter = mockk<VoteWeighter>()
            every { voteWeighter.getMinimalConfirmationWeight() } returns ElectionVoter.MIN_WEIGHT
            every { voteWeighter.get(finalVote.publicKey) } returns ElectionVoter.MIN_WEIGHT
            val discoverer = DependencyDiscoverer(voteWeighter, discoveryQueue)
            val voteDropped = VoteDropped(finalVote, VoteDropReason.TRANSACTION_DROPPED)
            discoverer.add(TransactionRejectionReason.PREVIOUS_NOT_FOUND, transaction)

            // When
            val admission =
                launch {
                    discoverer.process(voteDropped)
                }
            runCurrent()

            // Then
            assertFalse(admission.isCompleted)
            coVerify(exactly = 1) {
                discoveryQueue.queue(
                    match { it.transaction.hash == transaction.hash },
                    DiscoverySource.DEPENDENCY,
                )
            }

            // When
            admission.cancelAndJoin()
            coEvery {
                discoveryQueue.queue(any(), DiscoverySource.DEPENDENCY)
            } returns true
            discoverer.process(voteDropped)

            // Then
            coVerify(exactly = 2) {
                discoveryQueue.queue(
                    match { it.transaction.hash == transaction.hash },
                    DiscoverySource.DEPENDENCY,
                )
            }
        }

    @Test
    fun `final vote with high cached weight is not admitted below current confirmation weight`() =
        runTest {
            // Given
            val discoveryQueue = acceptingDiscoveryQueue()
            val representative = publicKey(10)
            val voteWeighter = voteWeighter(mapOf(representative to AttoAmount(9UL)))
            val discoverer = DependencyDiscoverer(voteWeighter, discoveryQueue)
            val transaction = transaction(2)
            discoverer.add(TransactionRejectionReason.PREVIOUS_NOT_FOUND, transaction)

            // When
            discoverer.process(
                VoteDropped(
                    finalVote(transaction.hash, representative, AttoAmount(100UL)),
                    VoteDropReason.TRANSACTION_DROPPED,
                ),
            )

            // Then
            coVerify(exactly = 0) {
                discoveryQueue.queue(any(), DiscoverySource.DEPENDENCY)
            }
        }

    @Test
    fun `final vote with high cached weight is not admitted when current representative weight is zero`() =
        runTest {
            // Given
            val discoveryQueue = acceptingDiscoveryQueue()
            val representative = publicKey(11)
            val voteWeighter = voteWeighter(emptyMap())
            val discoverer = DependencyDiscoverer(voteWeighter, discoveryQueue)
            val transaction = transaction(3)
            discoverer.add(TransactionRejectionReason.PREVIOUS_NOT_FOUND, transaction)

            // When
            discoverer.process(
                VoteDropped(
                    finalVote(transaction.hash, representative, AttoAmount(100UL)),
                    VoteDropReason.TRANSACTION_DROPPED,
                ),
            )

            // Then
            coVerify(exactly = 0) {
                discoveryQueue.queue(any(), DiscoverySource.DEPENDENCY)
            }
        }

    @Test
    fun `current representative weight admits final vote despite smaller cached weight`() =
        runTest {
            // Given
            val discoveryQueue = acceptingDiscoveryQueue()
            val representative = publicKey(12)
            val voteWeighter = voteWeighter(mapOf(representative to AttoAmount(10UL)))
            val discoverer = DependencyDiscoverer(voteWeighter, discoveryQueue)
            val transaction = transaction(4)
            discoverer.add(TransactionRejectionReason.PREVIOUS_NOT_FOUND, transaction)

            // When
            discoverer.process(
                VoteDropped(
                    finalVote(transaction.hash, representative, AttoAmount(1UL)),
                    VoteDropReason.TRANSACTION_DROPPED,
                ),
            )

            // Then
            coVerify(exactly = 1) {
                discoveryQueue.queue(
                    match { it.transaction.hash == transaction.hash },
                    DiscoverySource.DEPENDENCY,
                )
            }
        }

    @Test
    fun `retained votes use current weights and duplicate representatives count once`() =
        runTest {
            // Given
            val discoveryQueue = acceptingDiscoveryQueue()
            val representativeA = publicKey(13)
            val representativeB = publicKey(14)
            val currentWeights = mutableMapOf(representativeA to AttoAmount(6UL))
            val voteWeighter = voteWeighter(currentWeights)
            val discoverer = DependencyDiscoverer(voteWeighter, discoveryQueue)
            val transaction = transaction(5)
            discoverer.add(TransactionRejectionReason.PREVIOUS_NOT_FOUND, transaction)

            // When
            discoverer.process(
                VoteDropped(
                    finalVote(transaction.hash, representativeA, AttoAmount(1UL), marker = 15),
                    VoteDropReason.TRANSACTION_DROPPED,
                ),
            )
            discoverer.process(
                VoteDropped(
                    finalVote(transaction.hash, representativeA, AttoAmount(1UL), marker = 16),
                    VoteDropReason.TRANSACTION_DROPPED,
                ),
            )

            // Then
            coVerify(exactly = 0) {
                discoveryQueue.queue(any(), DiscoverySource.DEPENDENCY)
            }

            // When
            currentWeights[representativeA] = AttoAmount(8UL)
            currentWeights[representativeB] = AttoAmount(2UL)
            discoverer.process(
                VoteDropped(
                    finalVote(transaction.hash, representativeB, AttoAmount(1UL), marker = 17),
                    VoteDropReason.TRANSACTION_DROPPED,
                ),
            )

            // Then
            coVerify(exactly = 1) {
                discoveryQueue.queue(
                    match { it.transaction.hash == transaction.hash },
                    DiscoverySource.DEPENDENCY,
                )
            }
        }

    @Test
    fun `retained vote weight decrease prevents admission when another representative votes`() =
        runTest {
            // Given
            val discoveryQueue = acceptingDiscoveryQueue()
            val representativeA = publicKey(18)
            val representativeB = publicKey(19)
            val currentWeights = mutableMapOf(representativeA to AttoAmount(6UL))
            val voteWeighter = voteWeighter(currentWeights)
            val discoverer = DependencyDiscoverer(voteWeighter, discoveryQueue)
            val transaction = transaction(6)
            discoverer.add(TransactionRejectionReason.PREVIOUS_NOT_FOUND, transaction)

            // When
            discoverer.process(
                VoteDropped(
                    finalVote(transaction.hash, representativeA, AttoAmount(6UL), marker = 20),
                    VoteDropReason.TRANSACTION_DROPPED,
                ),
            )

            // Then
            coVerify(exactly = 0) {
                discoveryQueue.queue(any(), DiscoverySource.DEPENDENCY)
            }

            // When
            currentWeights[representativeA] = AttoAmount.MIN
            currentWeights[representativeB] = AttoAmount(6UL)
            discoverer.process(
                VoteDropped(
                    finalVote(transaction.hash, representativeB, AttoAmount(6UL), marker = 21),
                    VoteDropReason.TRANSACTION_DROPPED,
                ),
            )

            // Then
            coVerify(exactly = 0) {
                discoveryQueue.queue(any(), DiscoverySource.DEPENDENCY)
            }
        }

    private fun transaction(marker: Byte): Transaction =
        Transaction(
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

    private fun finalVote(
        blockHash: AttoHash,
        representativePublicKey: AttoPublicKey = publicKey(6),
        weight: AttoAmount = ElectionVoter.MIN_WEIGHT,
        marker: Byte = 5,
    ): Vote =
        Vote(
            hash = AttoHash(ByteArray(32) { marker }),
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = representativePublicKey,
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = blockHash,
            timestamp = AttoVote.finalTimestamp.toJavaInstant(),
            signature = AttoSignature(ByteArray(64) { (marker + 1).toByte() }),
            weight = weight,
            receivedAt = Instant.ofEpochSecond(marker.toLong()),
        )

    private fun publicKey(marker: Byte): AttoPublicKey = AttoPublicKey(ByteArray(32) { marker })

    private fun voteWeighter(currentWeights: Map<AttoPublicKey, AttoAmount>): VoteWeighter =
        mockk<VoteWeighter>().also { voteWeighter ->
            every { voteWeighter.get(any()) } answers {
                currentWeights[firstArg<AttoPublicKey>()] ?: AttoAmount.MIN
            }
            every { voteWeighter.getMinimalConfirmationWeight() } returns AttoAmount(10UL)
        }

    private fun acceptingDiscoveryQueue(): DiscoveryQueue =
        mockk<DiscoveryQueue>().also { discoveryQueue ->
            coEvery { discoveryQueue.queue(any(), DiscoverySource.DEPENDENCY) } returns true
        }
}
