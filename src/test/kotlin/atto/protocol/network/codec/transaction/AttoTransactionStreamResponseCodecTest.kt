package atto.protocol.network.codec.transaction

import atto.protocol.transaction.AttoTransactionStreamResponse
import cash.atto.commons.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

internal class AttoTransactionStreamResponseCodecTest {
    val privateKey = AttoPrivateKey("00".repeat(32).fromHexToByteArray())

    val codec = AttoTransactionStreamResponseCodec(AttoNetwork.LOCAL)

    @Test
    fun `should serialize and deserialize`() {
        // given
        val block = AttoOpenBlock(
            version = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = privateKey.toPublicKey(),
            balance = AttoAmount(100u),
            timestamp = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()),
            sendHashAlgorithm = AttoAlgorithm.V1,
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