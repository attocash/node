package org.atto.node.vote

import kotlinx.coroutines.test.runTest
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.commons.AttoSignature
import org.atto.protocol.vote.HashVote
import org.atto.protocol.vote.Vote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

internal class HashVoteQueueTest {
    val queue = HashVoteQueue(2)

    @Test
    fun `should return first transaction with higher weight`() = runTest {
        // given
        val weightedHashVote3 = WeightedHashVote(createHashVote(), 3UL)
        val weightedHashVote1 = WeightedHashVote(createHashVote(), 1UL)
        val weightedHashVote20 = WeightedHashVote(createHashVote(), 20UL)

        // when
        assertNull(queue.add(weightedHashVote3))
        assertNull(queue.add(weightedHashVote1))
        val deleted = queue.add(weightedHashVote20)

        // then
        assertEquals(weightedHashVote1, deleted)
        assertEquals(weightedHashVote20, queue.poll())
        assertEquals(weightedHashVote3, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `should return null when empty`() = runTest {
        assertNull(queue.poll())
    }

    private fun createHashVote(): HashVote {
        val vote = Vote(
            timestamp = Instant.now(),
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
        )

        return HashVote(
            hash = AttoHash(Random.nextBytes(ByteArray(32))),
            vote = vote,
            receivedTimestamp = Instant.now()
        )
    }
}