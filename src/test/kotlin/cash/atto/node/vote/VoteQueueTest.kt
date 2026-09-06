package cash.atto.node.vote

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.toAttoVersion
import cash.atto.node.transaction.Transaction
import cash.atto.node.vote.prioritization.VoteQueue
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

    @Test
    fun `should count a replaced vote once and return to zero after polling`() {
        // given
        val transaction = mockk<Transaction>()
        val originalVote = createVote(20UL)
        val replacementVote =
            createVote(20UL).copy(
                publicKey = originalVote.publicKey,
                blockHash = originalVote.blockHash,
                timestamp = originalVote.timestamp.plusSeconds(1),
            )
        val replacement = VoteQueue.TransactionVote(transaction, replacementVote)
        queue.add(VoteQueue.TransactionVote(transaction, originalVote))

        // when
        val dropped = queue.add(replacement)
        val replacementSize = queue.getSize()
        val polled = queue.poll()
        val drainedSize = queue.getSize()

        // then
        assertNull(dropped)
        assertEquals(1, replacementSize)
        assertEquals(replacement, polled)
        assertEquals(0, drainedSize)
        assertNull(queue.poll())
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
