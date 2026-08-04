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
            val voteWeighter = mockk<VoteWeighter>()
            every { voteWeighter.getMinimalConfirmationWeight() } returns ElectionVoter.MIN_WEIGHT
            val discoverer = DependencyDiscoverer(voteWeighter, discoveryQueue)
            val transaction = transaction(1)
            val finalVote = finalVote(transaction.hash)
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

    private fun finalVote(blockHash: AttoHash): Vote =
        Vote(
            hash = AttoHash(ByteArray(32) { 5 }),
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(ByteArray(32) { 6 }),
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = blockHash,
            timestamp = AttoVote.finalTimestamp.toJavaInstant(),
            signature = AttoSignature(ByteArray(64) { 7 }),
            weight = ElectionVoter.MIN_WEIGHT,
            receivedAt = Instant.now(),
        )
}
