package cash.atto.node.vote.prioritization

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoVote
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.node.Event
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.election.ElectionStarted
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteDropReason
import cash.atto.node.vote.VoteDropped
import cash.atto.node.vote.VoteReceived
import cash.atto.node.vote.VoteValidated
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

internal class VotePrioritizerTest {
    private val blockHash = AttoHash(ByteArray(32) { 1 })
    private val account = mockk<Account>()
    private val transaction =
        mockk<Transaction> {
            every { hash } returns blockHash
        }

    @Test
    fun `recoverable rejection allows votes for a later election`() =
        runTest {
            // Given
            val published = mutableListOf<Event>()
            val prioritizer = createPrioritizer(published)
            val bufferedVote = createVote(2)
            val laterVote = createVote(3)
            prioritizer.add(VoteReceived(URI("ws://peer:8080"), bufferedVote))

            // When
            prioritizer.process(
                TransactionRejected(
                    TransactionRejectionReason.PREVIOUS_NOT_FOUND,
                    "Previous transaction is missing",
                    account,
                    transaction,
                ),
            )
            prioritizer.process(ElectionStarted(account, transaction))
            prioritizer.add(VoteReceived(URI("ws://peer:8080"), laterVote))
            prioritizer.process()

            // Then
            assertEquals(
                listOf(bufferedVote to VoteDropReason.TRANSACTION_DROPPED),
                published.filterIsInstance<VoteDropped>().map { it.vote to it.reason },
            )
            assertEquals(
                listOf(laterVote),
                published.filterIsInstance<VoteValidated>().map { it.vote },
            )
        }

    @Test
    fun `permanent rejection drops later votes`() =
        runTest {
            // Given
            val published = mutableListOf<Event>()
            val prioritizer = createPrioritizer(published)
            val vote = createVote(2)
            prioritizer.process(
                TransactionRejected(
                    TransactionRejectionReason.INVALID_TRANSACTION,
                    "Transaction is invalid",
                    account,
                    transaction,
                ),
            )

            // When
            prioritizer.add(VoteReceived(URI("ws://peer:8080"), vote))
            prioritizer.process()

            // Then
            assertEquals(
                listOf(vote to VoteDropReason.TRANSACTION_DROPPED),
                published.filterIsInstance<VoteDropped>().map { it.vote to it.reason },
            )
            assertEquals(emptyList<Vote>(), published.filterIsInstance<VoteValidated>().map { it.vote })
        }

    private fun createPrioritizer(published: MutableList<Event>): VotePrioritizer {
        val eventPublisher = mockk<EventPublisher>()
        every { eventPublisher.publish(any()) } answers {
            published += firstArg<Event>()
            Unit
        }
        return VotePrioritizer(
            VotePrioritizationProperties().apply { queueMaxSize = 10 },
            eventPublisher,
        )
    }

    private fun createVote(marker: Byte): Vote =
        Vote(
            hash = AttoHash(ByteArray(32) { marker }),
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(ByteArray(32) { marker }),
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = blockHash,
            timestamp = AttoVote.finalTimestamp.toJavaInstant(),
            signature = AttoSignature(ByteArray(64) { marker }),
            weight = AttoAmount(1UL),
        )
}
