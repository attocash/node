package atto.protocol.network.codec.vote

import atto.protocol.vote.AttoVote
import atto.protocol.vote.AttoVoteSignature
import atto.protocol.vote.VoteType
import cash.atto.commons.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

internal class AttoVoteCodecTest {
    val privateKey = AttoPrivateKey("0".repeat(64).fromHexToByteArray())

    val codec = AttoVoteCodec(AttoSignatureCodec())

    @Test
    fun `should serialize and deserialize`() {
        // given
        val hash = AttoHash(Random.nextBytes(ByteArray(32)))

        val timestamp = Instant.now().toByteArray()
        val voteHash = AttoHash.hash(32, hash.value, timestamp)

        val expectedVoteSignature = AttoVoteSignature(
            timestamp = timestamp.toInstant(),
            publicKey = privateKey.toPublicKey(),
            signature = privateKey.sign(voteHash)
        )
        val expectedVote = AttoVote(
            type = VoteType.UNIQUE,
            hash = hash,
            signature = expectedVoteSignature,
        )

        // when
        val byteBuffer = codec.toByteBuffer(expectedVote)
        val vote = codec.fromByteBuffer(byteBuffer)

        // then
        assertEquals(expectedVote, vote)
    }
}