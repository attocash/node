package cash.atto.node.transaction

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoTransaction
import cash.atto.commons.AttoWork
import cash.atto.commons.toAtto
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.account.Account
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.protocol.AttoTransactionPush
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import kotlin.random.Random

internal class TransactionRebroadcasterTest {
    private val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
    private val rebroadcaster = TransactionRebroadcaster(messagePublisher)

    @Test
    @Timeout(2)
    fun `should rebroadcast with exclusions when seen before validated`() {
        runBlocking {
            // given
            val transaction = createAttoTransaction()
            val publicUri = URI("ws://node1:8080")
            val inboundMessage =
                InboundNetworkMessage(
                    source = MessageSource.WEBSOCKET,
                    publicUri = publicUri,
                    socketAddress = InetSocketAddress("127.0.0.1", 8080),
                    payload = AttoTransactionPush(transaction),
                )

            // when - normal order: seen first, then validated
            rebroadcaster.process(inboundMessage)
            rebroadcaster.process(createTransactionValidated(transaction))
            rebroadcaster.dequeue()

            // then
            val captured = mutableListOf<BroadcastNetworkMessage<*>>()
            verify { messagePublisher.publish(capture(captured)) }
            assertEquals(1, captured.size)
            assertEquals(setOf(publicUri), captured[0].exceptions)
        }
    }

    @Test
    @Timeout(2)
    fun `should rebroadcast without exclusions when validated before seen`() {
        runBlocking {
            // given
            val transaction = createAttoTransaction()

            // when - out of order: validated before seen
            rebroadcaster.process(createTransactionValidated(transaction))
            rebroadcaster.dequeue()

            // then - should still broadcast, just without exclusions
            val captured = mutableListOf<BroadcastNetworkMessage<*>>()
            verify { messagePublisher.publish(capture(captured)) }
            assertEquals(1, captured.size)
            assertTrue(captured[0].exceptions.isEmpty())
        }
    }

    @Test
    @Timeout(2)
    fun `should immediately broadcast REST transactions on validation`() {
        runBlocking {
            // given
            val transaction = createAttoTransaction()
            val publicUri = URI("ws://rest-node:8080")
            val inboundMessage =
                InboundNetworkMessage(
                    source = MessageSource.REST,
                    publicUri = publicUri,
                    socketAddress = InetSocketAddress("127.0.0.1", 8080),
                    payload = AttoTransactionPush(transaction),
                )

            // when
            rebroadcaster.process(inboundMessage)
            rebroadcaster.process(createTransactionValidated(transaction))
            rebroadcaster.dequeue()

            // then - two broadcasts: one immediate (REST) + one queued
            val captured = mutableListOf<BroadcastNetworkMessage<*>>()
            verify(exactly = 2) { messagePublisher.publish(capture(captured)) }
            // First is the immediate REST broadcast (no exclusions)
            assertTrue(captured[0].exceptions.isEmpty())
            // Second is the queued broadcast (holder was dropped by REST path, so created on the fly without exclusions)
            assertTrue(captured[1].exceptions.isEmpty())
        }
    }

    private fun createAttoTransaction(): AttoTransaction {
        val block =
            AttoReceiveBlock(
                network = AttoNetwork.LOCAL,
                version = 0U.toAttoVersion(),
                algorithm = AttoAlgorithm.V1,
                publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
                height = 2U.toAttoHeight(),
                balance = AttoAmount(100u),
                timestamp = Instant.now().toAtto(),
                previous = AttoHash(Random.nextBytes(ByteArray(32))),
                sendHashAlgorithm = AttoAlgorithm.V1,
                sendHash = AttoHash(ByteArray(32)),
            )
        return AttoTransaction(
            block = block,
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            work = AttoWork(Random.nextBytes(ByteArray(8))),
        )
    }

    private fun createTransactionValidated(attoTransaction: AttoTransaction): TransactionValidated {
        val transaction = attoTransaction.toTransaction()
        val account =
            Account(
                publicKey = transaction.publicKey,
                network = AttoNetwork.LOCAL,
                version = 0U.toAttoVersion(),
                algorithm = AttoAlgorithm.V1,
                height = 2L,
                balance = AttoAmount(100u),
                lastTransactionTimestamp = Instant.now(),
                lastTransactionHash = transaction.hash,
                representativeAlgorithm = AttoAlgorithm.V1,
                representativePublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            )
        return TransactionValidated(account = account, transaction = transaction)
    }
}
