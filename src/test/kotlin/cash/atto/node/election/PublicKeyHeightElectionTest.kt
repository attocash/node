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
import cash.atto.commons.toJavaInstant
import cash.atto.node.account.Account
import cash.atto.node.transaction.Transaction
import cash.atto.node.vote.Vote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

internal class PublicKeyHeightElectionTest {
    private fun minimalConfirmationWeightProvider(): () -> AttoAmount = { AttoAmount(10u) }

    private fun sampleAccount(): Account {
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))

        return Account(
            publicKey = publicKey,
            network = AttoNetwork.LOCAL,
            version = 0u.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            height = 1,
            balance = AttoAmount(0u),
            lastTransactionTimestamp = AttoNetwork.INITIAL_INSTANT.toJavaInstant(),
            lastTransactionHash = AttoHash(ByteArray(32)),
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = publicKey,
        )
    }

    private fun vote(
        publicKey: AttoPublicKey,
        blockHash: AttoHash,
        timestamp: Instant,
        weight: AttoAmount,
    ): Vote =
        Vote(
            hash = AttoHash(Random.nextBytes(ByteArray(32))),
            version = 0u.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = publicKey,
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = blockHash,
            timestamp = timestamp,
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            weight = weight,
        )

    @Test
    fun `should add transaction only once`() {
        // given
        val account = sampleAccount()
        val election = PublicKeyHeightElection(account, minimalConfirmationWeightProvider())
        val transaction = Transaction.sample()

        // when
        election.add(transaction)
        election.add(transaction)

        // then
        val provisionalLeader = election.getProvisionalLeader()
        assertSame(transaction, provisionalLeader.transaction)
        assertEquals(0, provisionalLeader.votes.size)
    }

    @Test
    fun `should accumulate votes and reach consensus`() {
        // given
        val account = sampleAccount()
        val election = PublicKeyHeightElection(account, minimalConfirmationWeightProvider())
        val transaction = Transaction.sample()
        val blockHash = transaction.hash
        val voter1 = AttoPublicKey(ByteArray(32) { 1 })
        val voter2 = AttoPublicKey(ByteArray(32) { 2 })
        val baseTime = Instant.now()
        val vote1 = vote(voter1, blockHash, baseTime, AttoAmount(4u))
        val vote2 = vote(voter2, blockHash, baseTime.plusSeconds(1), AttoAmount(7u))

        // when
        election.add(transaction)
        assertTrue(election.add(vote1))
        assertTrue(election.add(vote2))

        // then
        val consensus = election.getConsensus()
        assertNotNull(consensus)
        assertTrue(consensus!!.isConsensusReached())
        assertEquals(AttoAmount(11u), consensus.totalWeight)
    }

    @Test
    fun `should ignore votes for unknown transaction`() {
        // given
        val account = sampleAccount()
        val election = PublicKeyHeightElection(account, minimalConfirmationWeightProvider())
        val unknownTransaction = Transaction.sample()
        val vote =
            vote(
                publicKey = AttoPublicKey(ByteArray(32) { 3 }),
                blockHash = unknownTransaction.hash,
                timestamp = Instant.now(),
                weight = AttoAmount(5u),
            )

        // when
        val added = election.add(vote)
        val consensus = election.getConsensus()

        // then
        assertFalse(added)
        assertNull(consensus)
    }

    @Test
    fun `should keep only latest vote per voter`() {
        // given
        val account = sampleAccount()
        val election = PublicKeyHeightElection(account, minimalConfirmationWeightProvider())
        val transaction = Transaction.sample()
        val voter = AttoPublicKey(ByteArray(32) { 4 })
        val blockHash = transaction.hash
        val baseTime = Instant.now()
        val oldVote = vote(voter, blockHash, baseTime, AttoAmount(5u))
        val newerVote = vote(voter, blockHash, baseTime.plusSeconds(1), AttoAmount(8u))

        // when
        election.add(transaction)
        assertTrue(election.add(oldVote))
        assertTrue(election.add(newerVote))

        // then
        val transactionElection = election.getProvisionalLeader()
        assertEquals(1, transactionElection.votes.size)
        assertEquals(AttoAmount(8u), transactionElection.totalWeight)
    }

    @Test
    fun `should discard older vote from same voter`() {
        // given
        val account = sampleAccount()
        val election = PublicKeyHeightElection(account, minimalConfirmationWeightProvider())
        val transaction = Transaction.sample()
        val voter = AttoPublicKey(ByteArray(32) { 5 })
        val blockHash = transaction.hash
        val baseTime = Instant.now()
        val newerVote = vote(voter, blockHash, baseTime.plusSeconds(1), AttoAmount(8u))
        val oldVote = vote(voter, blockHash, baseTime, AttoAmount(5u))

        // when
        election.add(transaction)
        assertTrue(election.add(newerVote))
        assertFalse(election.add(oldVote))

        // then
        val transactionElection = election.getProvisionalLeader()
        assertEquals(1, transactionElection.votes.size)
        assertEquals(AttoAmount(8u), transactionElection.totalWeight)
    }

    @Test
    fun `should move provisional leader to transaction with higher weight`() {
        // given
        val account = sampleAccount()
        val election = PublicKeyHeightElection(account, minimalConfirmationWeightProvider())
        val transactionA = Transaction.sample()
        val transactionB = Transaction.sample()
        val voter = AttoPublicKey(ByteArray(32) { 6 })
        val baseTime = Instant.now()
        val voteA = vote(voter, transactionA.hash, baseTime, AttoAmount(3u))
        val voteB = vote(voter, transactionB.hash, baseTime.plusSeconds(1), AttoAmount(9u))

        // when
        election.add(transactionA)
        election.add(transactionB)
        assertTrue(election.add(voteA))
        assertTrue(election.add(voteB))

        // then
        val provisionalLeader = election.getProvisionalLeader()
        assertSame(transactionB, provisionalLeader.transaction)
    }

    @Test
    fun `should remove vote from competing transactions when new vote is added`() {
        // given
        val account = sampleAccount()
        val election = PublicKeyHeightElection(account, minimalConfirmationWeightProvider())
        val transactionA = Transaction.sample()
        val transactionB = Transaction.sample()
        val voter = AttoPublicKey(ByteArray(32) { 7 })
        val baseTime = Instant.now()
        val voteA = vote(voter, transactionA.hash, baseTime, AttoAmount(5u))
        val voteB = vote(voter, transactionB.hash, baseTime.plusSeconds(1), AttoAmount(5u))

        // when
        election.add(transactionA)
        election.add(transactionB)
        assertTrue(election.add(voteA))
        assertTrue(election.add(voteB))

        // then
        val provisionalLeader = election.getProvisionalLeader()
        assertSame(transactionB, provisionalLeader.transaction)
        assertEquals(1, provisionalLeader.votes.size)
        assertEquals(AttoAmount(5u), provisionalLeader.totalWeight)
    }

    @Test
    fun `should reach consensus ignoring weight of recently flipped voter`() {
        // given
        val account = sampleAccount()
        val election = PublicKeyHeightElection(account, minimalConfirmationWeightProvider())

        val transactionA = Transaction.sample()
        val transactionB = Transaction.sample()

        election.add(transactionA)
        election.add(transactionB)

        val baseTime = Instant.now()

        val stableVoters = (1..4).map { i -> AttoPublicKey(ByteArray(32) { i.toByte() }) }
        stableVoters.forEachIndexed { index, voter ->
            val voteA = vote(voter, transactionA.hash, baseTime.plusSeconds(index.toLong()), AttoAmount(3u))
            assertTrue(election.add(voteA))
        }

        val flipper = AttoPublicKey(ByteArray(32) { 9 })
        val voteB = vote(flipper, transactionB.hash, baseTime, AttoAmount(3u))
        assertTrue(election.add(voteB))

        val voteAFlipped = vote(flipper, transactionA.hash, baseTime.plusSeconds(10), AttoAmount(3u))

        // when
        assertTrue(election.add(voteAFlipped))
        val consensus = election.getConsensus()

        // then
        assertNotNull(consensus)
        assertSame(transactionA, consensus!!.transaction)
    }

    @Test
    fun `should NOT reach consensus when only recently flipped voter pushes over threshold`() {
        // given
        val account = sampleAccount()
        val election = PublicKeyHeightElection(account, minimalConfirmationWeightProvider())

        val transactionA = Transaction.sample()
        val transactionB = Transaction.sample()

        election.add(transactionA)
        election.add(transactionB)

        val baseTime = Instant.now()

        val stableVoters = (1..3).map { i -> AttoPublicKey(ByteArray(32) { i.toByte() }) }
        stableVoters.forEachIndexed { index, voter ->
            val voteA = vote(voter, transactionA.hash, baseTime.plusSeconds(index.toLong()), AttoAmount(3u))
            assertTrue(election.add(voteA))
        }

        val flipper = AttoPublicKey(ByteArray(32) { 20 })
        val voteB = vote(flipper, transactionB.hash, baseTime, AttoAmount(3u))
        assertTrue(election.add(voteB))

        val voteAFlipped = vote(flipper, transactionA.hash, baseTime.plusSeconds(10), AttoAmount(3u))

        // when
        assertTrue(election.add(voteAFlipped))
        val consensus = election.getConsensus()

        // then
        assertNull(consensus)
    }

    private fun AttoBlock.Companion.sample(publicKey: AttoPublicKey): AttoBlock =
        AttoReceiveBlock(
            version = 0u.toAttoVersion(),
            network = AttoNetwork.LOCAL,
            algorithm = AttoAlgorithm.V1,
            publicKey = publicKey,
            height = 1u.toAttoHeight(),
            balance = AttoAmount(0u),
            timestamp = AttoInstant.now(),
            previous = AttoHash(Random.nextBytes(ByteArray(32))),
            sendHashAlgorithm = AttoAlgorithm.V1,
            sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
        )

    private fun Transaction.Companion.sample(): Transaction {
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))

        return Transaction(
            AttoBlock.sample(publicKey),
            AttoSignature(Random.nextBytes(ByteArray(64))),
            AttoWork(Random.nextBytes(ByteArray(8))),
        )
    }
}
