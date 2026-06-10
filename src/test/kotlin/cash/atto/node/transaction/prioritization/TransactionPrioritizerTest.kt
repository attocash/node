package cash.atto.node.transaction.prioritization

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.node.Event
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionReceived
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.protocol.AttoTransactionPush
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import kotlin.random.Random

internal class TransactionPrioritizerTest {
    private val block =
        AttoReceiveBlock(
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            height = AttoHeight(2UL),
            balance = AttoAmount(1UL),
            timestamp = AttoInstant.now(),
            previous = AttoHash(Random.nextBytes(ByteArray(32))),
            sendHashAlgorithm = AttoAlgorithm.V1,
            sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
        )
    private val transaction =
        Transaction(
            block = block,
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            work = AttoWork(Random.nextBytes(ByteArray(8))),
        )
    private val account =
        Account(
            publicKey = block.publicKey,
            network = block.network,
            version = block.version,
            algorithm = block.algorithm,
            height = 1,
            balance = block.balance,
            lastTransactionTimestamp = block.timestamp.toJavaInstant(),
            lastTransactionHash = block.previous,
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
        )

    @Test
    fun `recoverable rejection allows the same transaction to be queued again`() {
        val published = mutableListOf<Event>()
        val eventPublisher = mockk<EventPublisher>()
        every { eventPublisher.publish(any()) } answers {
            published += firstArg<Event>()
            Unit
        }
        val prioritizer =
            TransactionPrioritizer(
                TransactionPrioritizationProperties().apply { groupMaxSize = 10 },
                eventPublisher,
                SimpleMeterRegistry(),
            )
        val message =
            InboundNetworkMessage(
                MessageSource.WEBSOCKET,
                URI("ws://127.0.0.1:8082"),
                InetSocketAddress("127.0.0.1", 8082),
                AttoTransactionPush(transaction.toAttoTransaction()),
            )

        prioritizer.add(message)
        prioritizer.process()
        prioritizer.add(message)
        prioritizer.process()

        prioritizer.process(
            TransactionRejected(
                TransactionRejectionReason.PREVIOUS_NOT_FOUND,
                "Previous transaction is missing",
                account,
                transaction,
            ),
        )
        prioritizer.add(message)
        prioritizer.process()

        assertEquals(
            listOf(transaction.hash, transaction.hash),
            published.filterIsInstance<TransactionReceived>().map { it.transaction.hash },
        )
    }
}
