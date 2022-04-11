package org.atto.node.transaction

import kotlinx.coroutines.test.runTest
import org.atto.commons.*
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import kotlin.random.Random


internal class TransactionQueueTest {
    val queue = TransactionQueue(2)

    @Test
    @Timeout(1)
    fun `should remove first entry when maxSize exceeded`() = runTest {
        // given
        val transaction200 = createTransaction(200u, Instant.now())
        val transaction50 = createTransaction(50u, Instant.now())
        val transaction15 = createTransaction(15u, Instant.now().minusSeconds(5))
        val transaction10 = createTransaction(10u, Instant.now().minusSeconds(160))

        // when
        assertNull(queue.add(transaction200))
        assertNull(queue.add(transaction50))
        assertNull(queue.add(transaction15))
        val deleted = queue.add(transaction10)

        // then
        assertEquals(transaction50, deleted)
        assertEquals(transaction10, queue.poll())
        assertEquals(transaction200, queue.poll())
        assertEquals(transaction15, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    @Timeout(1)
    fun `should return null when queue empty`() = runTest {
        assertNull(queue.poll())
    }


    private fun createTransaction(amount: ULong, receivedTimestamp: Instant): Transaction {
        val block = AttoBlockOld(
            type = AttoBlockType.RECEIVE,
            version = 0u,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            height = 1u,
            previous = AttoHash(Random.nextBytes(ByteArray(32))),
            representative = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            link = AttoLink.from(AttoHash(ByteArray(32))),
            balance = AttoAmount(0u),
            amount = AttoAmount(amount),
            timestamp = Instant.now()
        )
        return Transaction(
            block = block,
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            work = AttoWork(Random.nextBytes(ByteArray(8))),
            hash = AttoHash(Random.nextBytes(ByteArray(32))),
            status = TransactionStatus.RECEIVED,
            receivedTimestamp = receivedTimestamp
        )
    }
}