package org.atto.protocol.network.codec.transaction

import org.atto.commons.*
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random


internal class TransactionCodecTest {
    val seed = AttoSeed("1234567890123456789012345678901234567890123456789012345678901234".fromHexToByteArray())
    val privateKey = seed.toPrivateKey(0u)

    val codec = TransactionCodec(AttoNetwork.LOCAL)

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
        val attoTransaction = AttoTransaction(
            block = block,
            signature = privateKey.sign(block.getHash().value),
            work = AttoWork.work(block.publicKey, AttoNetwork.LOCAL),
        )

        val expectedTransaction = Transaction(
            attoTransaction,
            status = TransactionStatus.RECEIVED,
            receivedTimestamp = Instant.now(),
        )


        // when
        val byteBuffer = expectedTransaction.toByteBuffer();
        val transaction = codec.fromByteBuffer(byteBuffer)!!

        // then
        assertEquals(expectedTransaction, transaction)
        assertTrue(transaction.isValid(AttoNetwork.LOCAL))
    }
}