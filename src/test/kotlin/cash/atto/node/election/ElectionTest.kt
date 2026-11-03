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
import cash.atto.commons.toAtto
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountUpdated
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.transaction.TransactionValidated
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteValidated
import cash.atto.node.vote.weight.VoteWeighter
import io.micrometer.core.instrument.MeterRegistry
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
    lateinit var meterRegistry: MeterRegistry

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
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val transaction = Transaction.sample(publicKey = publicKey)
        val loser = Transaction.sample(publicKey = publicKey)

        // when
        runBlocking {
            election.start(TransactionValidated(account, transaction))
            election.start(TransactionValidated(account, loser))
            assertEquals(1, election.getSize())

            val accountUpdated = AccountUpdated(TransactionSource.ELECTION, account, account, transaction)
            election.process(accountUpdated)
        }

        // then
        assertEquals(0, election.getSize())
        verify {
            eventPublisher.publish(match { it is ElectionLost && it.transaction == loser })
        }
        verify(exactly = 0) {
            eventPublisher.publish(match { it is ElectionLost && it.transaction == transaction })
        }
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
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val transaction = Transaction.sample(publicKey = publicKey)
        val loser = Transaction.sample(publicKey = publicKey)
        val vote = Vote.sample(blockHash = transaction.hash, weight = minimalWeight)
        every { voteWeighter.get(vote.publicKey) } returns vote.weight

        // when
        runBlocking {
            election.start(TransactionValidated(account, transaction))
            election.start(TransactionValidated(account, loser))
            election.process(VoteValidated(transaction, vote))
        }

        // then
        assertEquals(0, election.getSize())
        verify {
            eventPublisher.publish(
                match { it is ElectionConsensusReached && it.transaction == transaction },
            )
            eventPublisher.publish(
                match { it is ElectionLost && it.transaction == loser },
            )
        }
        verify(exactly = 0) {
            eventPublisher.publish(match { it is ElectionLost && it.transaction == transaction })
        }
    }

    @Test
    fun `should reject competing candidate while consensus is pending persistence and admit next height after save`() {
        // Given
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val blockA = (AttoBlock.sample(publicKey = publicKey) as AttoReceiveBlock).copy(balance = AttoAmount(100UL))
        val transactionA =
            Transaction(
                block = blockA,
                signature = AttoSignature(Random.nextBytes(ByteArray(64))),
                work = AttoWork(Random.nextBytes(ByteArray(8))),
            )
        val transactionB = transactionA.copy(block = blockA.copy(sendHash = AttoHash(Random.nextBytes(ByteArray(32)))))
        val initialAccount =
            Account(
                publicKey = publicKey,
                network = blockA.network,
                version = blockA.version,
                algorithm = blockA.algorithm,
                height = 1,
                balance = AttoAmount.MIN,
                lastTransactionTimestamp = blockA.timestamp.toJavaInstant().minusSeconds(1),
                lastTransactionHash = blockA.previous,
                representativeAlgorithm = AttoAlgorithm.V1,
                representativePublicKey = publicKey,
            )
        val updatedAccount =
            initialAccount.copy(
                height = 2,
                balance = blockA.balance,
                lastTransactionTimestamp = blockA.timestamp.toJavaInstant(),
                lastTransactionHash = transactionA.hash,
            )
        val nextTransaction =
            transactionA.copy(
                block =
                    blockA.copy(
                        height = 3U.toAttoHeight(),
                        balance = AttoAmount(200UL),
                        previous = transactionA.hash,
                        sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
                        timestamp =
                            blockA.timestamp
                                .toJavaInstant()
                                .plusSeconds(1)
                                .toAtto(),
                    ),
            )
        val voteA = Vote.sample(blockHash = transactionA.hash, weight = minimalWeight)
        val voteB = Vote.sample(blockHash = transactionB.hash, weight = minimalWeight)
        every { voteWeighter.get(voteA.publicKey) } returns voteA.weight
        every { voteWeighter.get(voteB.publicKey) } returns voteB.weight

        // When
        runBlocking {
            election.start(TransactionValidated(initialAccount, transactionA))
            election.process(VoteValidated(transactionA, voteA))
            election.process(VoteValidated(transactionA, voteA))
            election.start(TransactionValidated(initialAccount, transactionB))
            election.process(VoteValidated(transactionB, voteB))
        }

        // Then
        assertEquals(0, election.getSize())
        verify(exactly = 1) {
            eventPublisher.publish(match { it is ElectionStarted && it.transaction == transactionA })
            eventPublisher.publish(match { it is ElectionConsensusReached && it.transaction == transactionA })
            eventPublisher.publish(match { it is ElectionRejected && it.account == initialAccount && it.transaction == transactionB })
            eventPublisher.publish(match { it is ElectionRejected })
        }
        verify(exactly = 0) {
            eventPublisher.publish(match { it is ElectionStarted && it.transaction == transactionB })
            eventPublisher.publish(match { it is ElectionConsensusReached && it.transaction == transactionB })
            eventPublisher.publish(match { it is ElectionRejected && it.transaction == transactionA })
        }

        // When
        runBlocking {
            election.process(AccountUpdated(TransactionSource.ELECTION, initialAccount, updatedAccount, transactionA))
            election.start(TransactionValidated(updatedAccount, nextTransaction))
        }

        // Then
        assertEquals(1, election.getSize())
        verify(exactly = 1) {
            eventPublisher.publish(match { it is ElectionStarted && it.account == updatedAccount && it.transaction == nextTransaction })
        }
        verify(exactly = 0) {
            eventPublisher.publish(match { it is ElectionRejected && it.transaction == nextTransaction })
        }
    }

    @ParameterizedTest
    @EnumSource(TransactionSource::class)
    fun `should reject delayed validation at saved height and admit next height`(source: TransactionSource) {
        // Given
        val saved = receiveTransaction()
        val competing = saved.competing()
        val previousAccount = accountBefore(saved)
        val savedAccount = previousAccount.after(saved)
        val next = saved.next()

        // When
        runBlocking {
            election.process(AccountUpdated(source, previousAccount, savedAccount, saved))
            election.start(TransactionValidated(previousAccount, saved))
            election.start(TransactionValidated(previousAccount, competing))
            election.start(TransactionValidated(savedAccount, next))
        }

        // Then
        assertEquals(setOf(next.toPublicKeyHeight()), election.getElections().keys)
        verify(exactly = 1) {
            eventPublisher.publish(match { it is ElectionRejected && it.account == previousAccount && it.transaction == saved })
            eventPublisher.publish(match { it is ElectionRejected && it.account == previousAccount && it.transaction == competing })
            eventPublisher.publish(match { it is ElectionStarted && it.account == savedAccount && it.transaction == next })
        }
        verify(exactly = 0) {
            eventPublisher.publish(match { it is ElectionStarted && it.transaction.height <= saved.height })
            eventPublisher.publish(match { it is ElectionLost })
        }
    }

    @Test
    fun `should preserve completed height and next election after delayed older account update`() {
        // Given
        val olderSaved = receiveTransaction()
        val newerSaved = olderSaved.next()
        val initialAccount = accountBefore(olderSaved)
        val olderAccount = initialAccount.after(olderSaved)
        val newerAccount = olderAccount.after(newerSaved)
        val staleCandidate = newerSaved.competing()
        val next = newerSaved.next()
        val nextVote = Vote.sample(blockHash = next.hash, weight = minimalWeight)
        every { voteWeighter.get(nextVote.publicKey) } returns nextVote.weight

        // When
        runBlocking {
            election.process(AccountUpdated(TransactionSource.BOOTSTRAP, olderAccount, newerAccount, newerSaved))
            election.start(TransactionValidated(newerAccount, next))
            election.process(AccountUpdated(TransactionSource.ELECTION, initialAccount, olderAccount, olderSaved))
            election.start(TransactionValidated(olderAccount, staleCandidate))
        }

        // Then
        assertEquals(setOf(next.toPublicKeyHeight()), election.getElections().keys)
        verify(exactly = 1) {
            eventPublisher.publish(match { it is ElectionRejected && it.transaction == staleCandidate })
        }
        verify(exactly = 0) {
            eventPublisher.publish(match { it is ElectionLost || it is ElectionRejected && it.transaction == next })
        }

        // When
        runBlocking {
            election.process(VoteValidated(next, nextVote))
        }

        // Then
        assertEquals(0, election.getSize())
        verify(exactly = 1) {
            eventPublisher.publish(match { it is ElectionConsensusReached && it.transaction == next })
        }
    }

    @Test
    fun `should reject historical candidates without reporting saved historical transaction as loser`() {
        // Given
        val historicalSaved = receiveTransaction()
        val historicalCompetitor = historicalSaved.competing()
        val laterSaved = historicalSaved.next()
        val initialAccount = accountBefore(historicalSaved)
        val historicalAccount = initialAccount.after(historicalSaved)
        val laterAccount = historicalAccount.after(laterSaved)

        // When
        runBlocking {
            election.start(TransactionValidated(initialAccount, historicalSaved))
            election.start(TransactionValidated(initialAccount, historicalCompetitor))
            election.process(AccountUpdated(TransactionSource.BOOTSTRAP, historicalAccount, laterAccount, laterSaved))
        }

        // Then
        assertEquals(0, election.getSize())
        verify(exactly = 1) {
            eventPublisher.publish(match { it is ElectionRejected && it.account == initialAccount && it.transaction == historicalSaved })
            eventPublisher.publish(
                match { it is ElectionRejected && it.account == initialAccount && it.transaction == historicalCompetitor },
            )
        }
        verify(exactly = 0) {
            eventPublisher.publish(match { it is ElectionLost })
        }
    }

    @Test
    fun `should close discarded consensus height and preserve valid next height after duplicate discard`() {
        // Given
        val saved = receiveTransaction()
        val competing = saved.competing()
        val initialAccount = accountBefore(saved)
        val savedAccount = initialAccount.after(saved)
        val next = saved.next()
        val savedVote = Vote.sample(blockHash = saved.hash, weight = minimalWeight)
        val nextVote = Vote.sample(blockHash = next.hash, weight = minimalWeight)
        every { voteWeighter.get(savedVote.publicKey) } returns savedVote.weight
        every { voteWeighter.get(nextVote.publicKey) } returns nextVote.weight
        val discarded = ElectionDiscarded(savedAccount, saved)

        // When
        runBlocking {
            election.start(TransactionValidated(initialAccount, saved))
            election.process(VoteValidated(saved, savedVote))
            election.process(discarded)
            election.start(TransactionValidated(savedAccount, next))
            election.process(discarded)
            election.start(TransactionValidated(initialAccount, competing))
            election.process(VoteValidated(next, nextVote))
        }

        // Then
        assertEquals(0, election.getSize())
        verify(exactly = 1) {
            eventPublisher.publish(match { it is ElectionConsensusReached && it.transaction == saved })
            eventPublisher.publish(match { it is ElectionConsensusReached && it.transaction == next })
            eventPublisher.publish(match { it is ElectionRejected && it.transaction == competing })
        }
        verify(exactly = 0) {
            eventPublisher.publish(match { it is ElectionStarted && it.transaction == competing })
            eventPublisher.publish(match { it is ElectionLost })
        }
    }

    @Test
    fun `should reconcile stale active candidates from discarded consensus account snapshot`() {
        // Given
        val discardedTransaction = receiveTransaction()
        val historicalSaved = discardedTransaction.next()
        val historicalCompetitor = historicalSaved.competing()
        val latestSaved = historicalSaved.next()
        val initialAccount = accountBefore(discardedTransaction)
        val discardedAccount = initialAccount.after(discardedTransaction)
        val historicalAccount = discardedAccount.after(historicalSaved)
        val latestAccount = historicalAccount.after(latestSaved)
        val next = latestSaved.next()
        val vote = Vote.sample(blockHash = discardedTransaction.hash, weight = minimalWeight)
        every { voteWeighter.get(vote.publicKey) } returns vote.weight

        // When
        runBlocking {
            election.start(TransactionValidated(initialAccount, discardedTransaction))
            election.process(VoteValidated(discardedTransaction, vote))
            election.start(TransactionValidated(discardedAccount, historicalSaved))
            election.start(TransactionValidated(discardedAccount, historicalCompetitor))
            election.start(TransactionValidated(latestAccount, next))
            election.process(ElectionDiscarded(latestAccount, discardedTransaction))
        }

        // Then
        assertEquals(setOf(next.toPublicKeyHeight()), election.getElections().keys)
        verify(exactly = 1) {
            eventPublisher.publish(match { it is ElectionRejected && it.transaction == historicalSaved })
            eventPublisher.publish(match { it is ElectionRejected && it.transaction == historicalCompetitor })
        }
        verify(exactly = 0) {
            eventPublisher.publish(match { it is ElectionLost })
            eventPublisher.publish(match { it is ElectionRejected && it.transaction == next })
        }
    }

    @Test
    fun `should clear completed height cache and allow validation after cache miss`() {
        // Given
        val saved = receiveTransaction()
        val previousAccount = accountBefore(saved)
        val savedAccount = previousAccount.after(saved)
        runBlocking {
            election.process(AccountUpdated(TransactionSource.ELECTION, previousAccount, savedAccount, saved))
        }

        // When
        election.clear()
        runBlocking {
            election.start(TransactionValidated(previousAccount, saved))
        }

        // Then
        assertEquals(setOf(saved.toPublicKeyHeight()), election.getElections().keys)
        verify(exactly = 1) {
            eventPublisher.publish(match { it is ElectionStarted && it.transaction == saved })
        }
        verify(exactly = 0) {
            eventPublisher.publish(match { it is ElectionRejected })
        }
    }

    @Test
    fun `should clear pending consensus state`() {
        // given
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val transactionA = Transaction.sample(publicKey = publicKey)
        val transactionB = Transaction.sample(publicKey = publicKey)
        val voteA = Vote.sample(blockHash = transactionA.hash, weight = minimalWeight)
        every { voteWeighter.get(voteA.publicKey) } returns voteA.weight

        // when
        runBlocking {
            election.start(TransactionValidated(account, transactionA))
            election.process(VoteValidated(transactionA, voteA))
            election.clear()
            election.start(TransactionValidated(account, transactionB))
        }

        // then
        verify(exactly = 1) {
            eventPublisher.publish(match { it is ElectionConsensusReached && it.transaction == transactionA })
            eventPublisher.publish(match { it is ElectionStarted && it.transaction == transactionB })
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
        every { voteWeighter.get(voteA.publicKey) } returns voteA.weight
        every { voteWeighter.get(voteB.publicKey) } returns voteB.weight

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
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val oldTransaction = Transaction.sample(publicKey, receivedAt = Instant.now().minusSeconds(900))
        val loser = Transaction.sample(publicKey, receivedAt = Instant.now().minusSeconds(900))
        val vote = Vote.sample(blockHash = oldTransaction.hash, weight = AttoAmount(1UL))
        every { voteWeighter.get(vote.publicKey) } returns vote.weight

        // when
        runBlocking {
            election.start(TransactionValidated(account, oldTransaction))
            election.start(TransactionValidated(account, loser))
            election.process(VoteValidated(oldTransaction, vote))
            election.expireOld()
        }

        // then
        assertEquals(0, election.getSize())
        verify {
            eventPublisher.publish(
                match { it is ElectionExpired && it.transaction == oldTransaction },
            )
            eventPublisher.publish(
                match { it is ElectionLost && it.transaction == loser },
            )
        }
        verify(exactly = 0) {
            eventPublisher.publish(match { it is ElectionLost && it.transaction == oldTransaction })
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
        every { voteWeighter.get(vote1.publicKey) } returns vote1.weight
        every { voteWeighter.get(vote2.publicKey) } returns vote2.weight

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
        every { voteWeighter.get(voterKey) } returns newerVote.weight

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

    private fun receiveTransaction(): Transaction {
        val transaction = Transaction.sample()
        val block = transaction.block as AttoReceiveBlock
        return transaction.copy(block = block.copy(balance = AttoAmount(100UL)))
    }

    private fun Transaction.competing(): Transaction =
        copy(block = (block as AttoReceiveBlock).copy(sendHash = AttoHash(Random.nextBytes(ByteArray(32)))))

    private fun Transaction.next(): Transaction =
        copy(
            block =
                (block as AttoReceiveBlock).copy(
                    height = (height.value + 1UL).toAttoHeight(),
                    balance = block.balance + AttoAmount(100UL),
                    previous = hash,
                    sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
                    timestamp =
                        block.timestamp
                            .toJavaInstant()
                            .plusSeconds(1)
                            .toAtto(),
                ),
        )

    private fun accountBefore(transaction: Transaction): Account =
        Account(
            publicKey = transaction.publicKey,
            network = transaction.block.network,
            version = transaction.block.version,
            algorithm = transaction.block.algorithm,
            height = transaction.height.value.toLong() - 1,
            balance = AttoAmount.MIN,
            lastTransactionTimestamp =
                transaction.block.timestamp
                    .toJavaInstant()
                    .minusSeconds(1),
            lastTransactionHash = (transaction.block as AttoReceiveBlock).previous,
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = transaction.publicKey,
        )

    private fun Account.after(transaction: Transaction): Account =
        copy(
            height = transaction.height.value.toLong(),
            balance = transaction.block.balance,
            lastTransactionTimestamp = transaction.block.timestamp.toJavaInstant(),
            lastTransactionHash = transaction.hash,
        )

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
