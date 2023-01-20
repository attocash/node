package org.atto.protocol.network.codec.transaction

import org.atto.commons.*
import org.atto.protocol.transaction.AttoTransactionStreamResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

internal class AttoTransactionStreamResponseCodecTest {
    val privateKey = AttoPrivateKey("00".repeat(32).fromHexToByteArray())

    val codec = AttoTransactionStreamResponseCodec(AttoNetwork.LOCAL)

    @Test
    fun `should serialize and deserialize`() {
        // given
        val block = AttoOpenBlock(
            version = 0u,
            publicKey = privateKey.toPublicKey(),
            balance = AttoAmount(100u),
            timestamp = Instant.now().toByteArray().toInstant(),
            sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
            representative = privateKey.toPublicKey(),
        )

        val transaction = AttoTransaction(
            block = block,
            signature = privateKey.sign(block.hash),
            work = AttoWork.work(AttoNetwork.LOCAL, block.timestamp, block.publicKey)
        )

        val expectedRequest = AttoTransactionStreamResponse(listOf(transaction, transaction))

        // when
        val byteBuffer = codec.toByteBuffer(expectedRequest)
        val request = codec.fromByteBuffer(byteBuffer)

        // then
        assertEquals(expectedRequest, request)

    }
}