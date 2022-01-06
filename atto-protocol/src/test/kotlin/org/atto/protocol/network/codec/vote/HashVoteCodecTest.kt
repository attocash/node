package org.atto.protocol.network.codec.vote

import org.atto.commons.*
import org.atto.protocol.vote.HashVote
import org.atto.protocol.vote.Vote
import org.atto.protocol.vote.VoteType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

internal class HashVoteCodecTest {
    val privateKey = AttoSeeds.generateSeed().toPrivateKey(0u)

    val codec = HashVoteCodec(VoteCodec())

    @Test
    fun `should serialize and deserialize`() {
        // given
        val hash = AttoHash(Random.nextBytes(ByteArray(32)))

        val timestamp = Instant.now().toByteArray()
        val voteHash = AttoHashes.hash(32, hash.value + timestamp)

        val vote = Vote(
            timestamp = timestamp.toInstant(),
            publicKey = privateKey.toPublicKey(),
            signature = privateKey.sign(voteHash)
        )
        val expectedHashVote = HashVote(
            type = VoteType.UNIQUE,
            hash = hash,
            vote = vote,
            receivedTimestamp = Instant.now()
        )

        // when
        val byteArray = codec.toByteArray(expectedHashVote)
        val hashVote = codec.fromByteArray(byteArray)!!

        // then
        assertEquals(expectedHashVote, hashVote)
        assertTrue(hashVote.isValid())
    }
}