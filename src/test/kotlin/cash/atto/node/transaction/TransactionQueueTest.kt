package cash.atto.node.transaction

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.transaction.priotization.TransactionQueue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toKotlinInstant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import kotlin.random.Random

internal class TransactionQueueTest {
    private val queue = TransactionQueue(2)

    @Test
    @Timeout(2)
    fun `should remove first entry when maxSize exceeded`() {
        // given
        val transaction200 = createTransaction(200u, Instant.now().minusSeconds(160), Instant.now())
        val transaction50 = createTransaction(50u, Instant.now(), Instant.now())
        val transaction15 = createTransaction(15u, Instant.now().minusSeconds(10), Instant.now())
        val transaction10 = createTransaction(10u, Instant.now().minusSeconds(5), Instant.now())

        // when
        assertNull(queue.add(transaction200))
        assertNull(queue.add(transaction50))
        assertNull(queue.add(transaction15))
        val deleted = queue.add(transaction10)

        // then
        assertEquals(transaction15, deleted)
        assertEquals(transaction200, queue.poll())
        assertEquals(transaction50, queue.poll())
        assertEquals(transaction10, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    @Timeout(2)
    fun `should use lower group when maxGroup is lower than group`() {
        // given
        val queue = TransactionQueue(2, 17)

        val transaction200 = createTransaction(200u, Instant.now().minusSeconds(160), Instant.now())
        val transaction50 = createTransaction(50u, Instant.now(), Instant.now())
        val transaction15 = createTransaction(15u, Instant.now().minusSeconds(10), Instant.now())

        // when
        assertNull(queue.add(transaction200))
        assertNull(queue.add(transaction50))
        val deleted = queue.add(transaction15)

        // then
        assertEquals(transaction200, deleted)
        assertEquals(transaction50, queue.poll())
        assertEquals(transaction15, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    @Timeout(1)
    fun `should return null when queue empty`() =
        runBlocking {
            assertNull(queue.poll())
        }

    private fun createTransaction(
        amount: ULong,
        timestamp: Instant,
        receivedAt: Instant,
    ): Transaction {
        val block =
            AttoReceiveBlock(
                version = 0U.toAttoVersion(),
                algorithm = AttoAlgorithm.V1,
                publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
                height = 2U.toAttoHeight(),
                balance = AttoAmount(amount),
                timestamp = timestamp.toKotlinInstant(),
                previous = AttoHash(Random.nextBytes(ByteArray(32))),
                sendHashAlgorithm = AttoAlgorithm.V1,
                sendHash = AttoHash(ByteArray(32)),
            )
        return Transaction(
            block = block,
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            work = AttoWork(Random.nextBytes(ByteArray(8))),
            receivedAt = receivedAt,
        )
    }
}
