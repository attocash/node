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
        val block = AttoBlock(
            type = AttoBlockType.OPEN,
            version = 0u,
            publicKey = privateKey.toPublicKey(),
            height = 0u,
            previous = AttoHash(ByteArray(32)),
            representative = privateKey.toPublicKey(),
            link = AttoLink.from(AttoHash(Random.nextBytes(ByteArray(32)))),
            balance = AttoAmount(100u),
            amount = AttoAmount(100u),
            timestamp = Instant.now().toByteArray().toInstant()
        )
        val expectedTransaction = Transaction(
            status = TransactionStatus.RECEIVED,
            block = block,
            signature = privateKey.sign(block.getHash().value),
            work = AttoWork.work(block.publicKey, AttoNetwork.LOCAL),
            receivedTimestamp = Instant.now()
        )

        // when
        val transaction = codec.fromByteArray(expectedTransaction.toByteArray())!!

        // then
        assertEquals(expectedTransaction, transaction)
        assertTrue(transaction.isValid(AttoNetwork.LOCAL))
    }
}