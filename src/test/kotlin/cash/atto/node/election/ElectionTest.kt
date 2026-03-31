package cash.atto.node.election

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoBlock
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountUpdated
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.transaction.TransactionValidated
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteValidated
import cash.atto.node.vote.weight.VoteWeighter
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import kotlin.random.Random

@ExtendWith(MockKExtension::class)
class ElectionTest {
    @MockK
    lateinit var properties: ElectionProperties

    @RelaxedMockK
    lateinit var voteWeighter: VoteWeighter

    @RelaxedMockK
    lateinit var eventPublisher: EventPublisher

    @RelaxedMockK
    lateinit var account: Account

    @InjectMockKs
    lateinit var election: Election

    private val minimalWeight = AttoAmount(1000UL)

    @BeforeEach
    fun beforeEach() {
        every { voteWeighter.getMinimalConfirmationWeight() } returns minimalWeight
        every { properties.expiringAfterTimeInSeconds } returns 300L
        every { properties.expiredAfterTimeInSeconds } returns 600L
        election.clear()
    }

    @Test
    fun `should start election when TransactionValidated event is received`() {
        // given
        val transaction = Transaction.sample()
        val event = TransactionValidated(account, transaction)

        // when
        runBlocking {
            election.start(event)
        }

        // then
        assertEquals(1, election.getSize())
        verify {
            eventPublisher.publish(
                match { it is ElectionStarted && it.transaction == transaction },
            )
        }
    }

    @Test
    fun `should not duplicate election for same transaction`() {
        // given
        val transaction = Transaction.sample()

        // when
        runBlocking {
            election.start(TransactionValidated(account, transaction))
            election.start(TransactionValidated(account, transaction))
        }

        // then
        assertEquals(1, election.getSize())
    }

    @Test
    fun `should remove election when AccountUpdated event is received`() {
        // given
        val transaction = Transaction.sample()

        // when
        runBlocking {
            election.start(TransactionValidated(account, transaction))
            assertEquals(1, election.getSize())

            val accountUpdated = AccountUpdated(TransactionSource.ELECTION, account, account, transaction)
            election.process(accountUpdated)
        }

        // then
        assertEquals(0, election.getSize())
    }

    @Test
    fun `should ignore vote when no election exists`() {
        // given
        val transaction = Transaction.sample()
        val vote = Vote.sample(blockHash = transaction.hash, weight = minimalWeight)
        val event = VoteValidated(transaction, vote)

        // when
        runBlocking {
            election.process(event)
        }

        // then
        assertEquals(0, election.getSize())
        verify(exactly = 0) {
            eventPublisher.publish(match { it is ElectionConsensusReached })
        }
    }

    @Test
    fun `should reach consensus when vote weight meets threshold`() {
        // given
        val transaction = Transaction.sample()
        val vote = Vote.sample(blockHash = transaction.hash, weight = minimalWeight)

        // when
        runBlocking {
            election.start(TransactionValidated(account, transaction))
            election.process(VoteValidated(transaction, vote))
        }

        // then
        assertEquals(0, election.getSize())
        verify {
            eventPublisher.publish(
                match { it is ElectionConsensusReached && it.transaction == transaction },
            )
        }
    }

    @Test
    fun `should publish consensus changed when provisional leader changes`() {
        // given
        every { voteWeighter.getMinimalConfirmationWeight() } returns AttoAmount(2000UL)
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val transactionA = Transaction.sample(publicKey = publicKey)
        val transactionB = Transaction.sample(publicKey = publicKey)
        val voteA = Vote.sample(blockHash = transactionA.hash, weight = AttoAmount(100UL))
        val voteB = Vote.sample(blockHash = transactionB.hash, weight = AttoAmount(500UL))

        // when
        runBlocking {
            election.start(TransactionValidated(account, transactionA))
            election.start(TransactionValidated(account, transactionB))
            // First make transactionA the definitive leader
            election.process(VoteValidated(transactionA, voteA))
            // Then transactionB overtakes
            election.process(VoteValidated(transactionB, voteB))
        }

        // then
        assertEquals(1, election.getSize())
        verify {
            eventPublisher.publish(
                match { it is ElectionConsensusChanged && it.transaction == transactionB },
            )
        }
    }

    @Test
    fun `should expire elections older than expiring threshold`() {
        // given
        val oldTransaction = Transaction.sample(receivedAt = Instant.now().minusSeconds(600))

        // when
        runBlocking {
            election.start(TransactionValidated(account, oldTransaction))
            election.notifyExpiring()
        }

        // then
        verify {
            eventPublisher.publish(
                match { it is ElectionExpiring && it.transaction == oldTransaction },
            )
        }
    }

    @Test
    fun `should remove staled elections older than expired threshold`() {
        // given
        val oldTransaction = Transaction.sample(receivedAt = Instant.now().minusSeconds(900))

        // when
        runBlocking {
            election.start(TransactionValidated(account, oldTransaction))
            election.expireOld()
        }

        // then
        assertEquals(0, election.getSize())
        verify {
            eventPublisher.publish(
                match { it is ElectionExpired && it.transaction == oldTransaction },
            )
        }
    }

    @Test
    fun `should clear all elections`() {
        // given
        val transaction = Transaction.sample()

        // when
        runBlocking {
            election.start(TransactionValidated(account, transaction))
            assertEquals(1, election.getSize())
            election.clear()
        }

        // then
        assertEquals(0, election.getSize())
    }

    @Test
    fun `should not publish consensus changed when provisional leader stays the same`() {
        // given
        every { voteWeighter.getMinimalConfirmationWeight() } returns AttoAmount(2000UL)
        val transaction = Transaction.sample()
        val vote1 = Vote.sample(blockHash = transaction.hash, weight = AttoAmount(500UL))
        val vote2 = Vote.sample(blockHash = transaction.hash, weight = AttoAmount(500UL))

        // when
        runBlocking {
            election.start(TransactionValidated(account, transaction))
            election.process(VoteValidated(transaction, vote1))
            election.process(VoteValidated(transaction, vote2))
        }

        // then
        assertEquals(1, election.getSize())
        verify(exactly = 0) {
            eventPublisher.publish(
                match { it is ElectionConsensusChanged },
            )
        }
    }

    @Test
    fun `should ignore old vote for same voter`() {
        // given
        every { voteWeighter.getMinimalConfirmationWeight() } returns AttoAmount(2000UL)
        val transaction = Transaction.sample()
        val voterKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val newerVote =
            Vote.sample(
                blockHash = transaction.hash,
                publicKey = voterKey,
                weight = AttoAmount(500UL),
                timestamp = Instant.now(),
            )
        val olderVote =
            Vote.sample(
                blockHash = transaction.hash,
                publicKey = voterKey,
                weight = AttoAmount(500UL),
                timestamp = Instant.now().minusSeconds(10),
            )

        // when
        runBlocking {
            election.start(TransactionValidated(account, transaction))
            election.process(VoteValidated(transaction, newerVote))
            election.process(VoteValidated(transaction, olderVote))
        }

        // then
        assertEquals(1, election.getSize())
        // No ConsensusChanged event since leader stays the same (single transaction)
        verify(exactly = 0) {
            eventPublisher.publish(
                match { it is ElectionConsensusChanged },
            )
        }
    }

    private fun AttoBlock.Companion.sample(publicKey: AttoPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))): AttoBlock =
        AttoReceiveBlock(
            version = 0U.toAttoVersion(),
            network = AttoNetwork.LOCAL,
            algorithm = AttoAlgorithm.V1,
            publicKey = publicKey,
            height = 2U.toAttoHeight(),
            balance = AttoAmount.MAX,
            timestamp = AttoInstant.now(),
            previous = AttoHash(Random.nextBytes(ByteArray(32))),
            sendHashAlgorithm = AttoAlgorithm.V1,
            sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
        )

    private fun Transaction.Companion.sample(
        publicKey: AttoPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
        receivedAt: Instant = Instant.now(),
    ): Transaction =
        Transaction(
            AttoBlock.sample(publicKey = publicKey),
            AttoSignature(Random.nextBytes(ByteArray(64))),
            AttoWork(Random.nextBytes(ByteArray(8))),
            receivedAt = receivedAt,
        )

    private fun Vote.Companion.sample(
        blockHash: AttoHash,
        weight: AttoAmount,
        publicKey: AttoPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
        timestamp: Instant = Instant.now(),
    ): Vote =
        Vote(
            hash = AttoHash(Random.nextBytes(ByteArray(32))),
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = publicKey,
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = blockHash,
            timestamp = timestamp,
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            weight = weight,
        )
}
