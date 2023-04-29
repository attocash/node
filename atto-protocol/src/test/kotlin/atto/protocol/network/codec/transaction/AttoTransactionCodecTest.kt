package atto.protocol.network.codec.transaction

import atto.commons.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random


internal class AttoTransactionCodecTest {
    val privateKey = AttoPrivateKey("00".repeat(32).fromHexToByteArray())

    val codec = AttoTransactionCodec(AttoNetwork.LOCAL)

    @Test
    fun `should deserialize and serialize`() {
        // given
        val block = AttoOpenBlock(
            version = 0u,
            publicKey = privateKey.toPublicKey(),
            balance = AttoAmount(100u),
            timestamp = Instant.now().toByteArray().toInstant(),
            sendHash = AttoHash(Random.Default.nextBytes(ByteArray(32))),
            representative = privateKey.toPublicKey(),
        )

        val expectedTransaction = AttoTransaction(
            block = block,
            signature = privateKey.sign(block.hash),
            work = AttoWork.work(AttoNetwork.LOCAL, block.timestamp, block.publicKey)
        )


        // when
        val byteBuffer = expectedTransaction.toByteBuffer();
        val transaction = codec.fromByteBuffer(byteBuffer)!!

        // then
        assertEquals(expectedTransaction, transaction)
        assertTrue(transaction.isValid(AttoNetwork.LOCAL))
    }
}