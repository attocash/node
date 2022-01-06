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
        val hashVote1 = createHashVote()
        val hashVote20 = createHashVote()
        val hashVote3 = createHashVote()

        // when
        assertNull(queue.add(1UL, hashVote1))
        assertNull(queue.add(20UL, hashVote20))
        val deleted = queue.add(5UL, hashVote3)

        // then
        assertEquals(hashVote1, deleted)
        assertEquals(hashVote20, queue.poll())
        assertEquals(hashVote3, queue.poll())
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