package org.atto.node.vote

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.commons.AttoSignature
import org.atto.node.transaction.Transaction
import org.atto.node.vote.priotization.VoteQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

internal class VoteQueueTest {
    val queue = VoteQueue(2)

    @Test
    fun `should return first transaction with higher weight`() = runBlocking {
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
    fun `should return null when empty`() = runBlocking {
        assertNull(queue.poll())
    }

    private fun createVote(weight: ULong): Vote {
        return Vote(
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            hash = AttoHash(Random.nextBytes(ByteArray(32))),
            timestamp = Instant.now(),
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            receivedTimestamp = Instant.now(),
            weight = AttoAmount(weight)
        )
    }
}