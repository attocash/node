package cash.atto.node.vote

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.toAttoVersion
import cash.atto.node.transaction.Transaction
import cash.atto.node.vote.priotization.VoteQueue
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

internal class VoteQueueTest {
    val queue = VoteQueue(2)

    @Test
    fun `should return first transaction with higher weight`() =
        runBlocking {
            // given
            val transaction = mockk<Transaction>()
            val vote3 = VoteQueue.TransactionVote(transaction, createVote(3UL))
            val vote1 = VoteQueue.TransactionVote(transaction, createVote(1UL))
            val vote20 = VoteQueue.TransactionVote(transaction, createVote(20UL))

            // when
            assertNull(queue.add(vote3))
            assertNull(queue.add(vote1))
            val deleted = queue.add(vote20)

            // then
            assertEquals(vote1, deleted)
            assertEquals(vote20, queue.poll())
            assertEquals(vote3, queue.poll())
            assertNull(queue.poll())
        }

    @Test
    fun `should return null when empty`() =
        runBlocking {
            assertNull(queue.poll())
        }

    @Test
    fun `should allow same weight votes`() =
        runBlocking {
            // given
            val transaction = mockk<Transaction>()
            val firstVote = VoteQueue.TransactionVote(transaction, createVote(20UL))
            val secondVote = VoteQueue.TransactionVote(transaction, createVote(20UL))

            // when
            assertNull(queue.add(firstVote))
            assertNull(queue.add(secondVote))

            // then
            assertEquals(2, queue.getSize())
        }

    private fun createVote(weight: ULong): Vote =
        Vote(
            hash = AttoHash(Random.nextBytes(ByteArray(32))),
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = AttoHash(Random.nextBytes(ByteArray(32))),
            timestamp = Instant.now(),
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            weight = AttoAmount(weight),
        )
}
