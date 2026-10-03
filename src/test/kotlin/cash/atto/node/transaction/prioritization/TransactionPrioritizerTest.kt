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
import cash.atto.node.election.ElectionLost
import cash.atto.node.election.ElectionStarted
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
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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
        // Given
        val published = CopyOnWriteArrayList<Event>()
        val eventPublisher = mockk<EventPublisher>()
        every { eventPublisher.publish(any()) } answers {
            published += firstArg<Event>()
        }
        val prioritizer =
            TransactionPrioritizer(
                TransactionPrioritizationProperties().apply {
                    groupMaxSize = 10
                    maxActiveElections = 1_000
                },
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

        try {
            // When
            prioritizer.add(message)
            awaitCondition { published.filterIsInstance<TransactionReceived>().size == 1 }
            prioritizer.add(message)
            prioritizer.process(
                TransactionRejected(
                    TransactionRejectionReason.PREVIOUS_NOT_FOUND,
                    "Previous transaction is missing",
                    account,
                    transaction,
                ),
            )
            prioritizer.add(message)

            // Then
            awaitCondition { published.filterIsInstance<TransactionReceived>().size == 2 }
            assertEquals(
                listOf(transaction.hash, transaction.hash),
                published.filterIsInstance<TransactionReceived>().map { it.transaction.hash },
            )
        } finally {
            prioritizer.stop()
        }
    }

    @Test
    fun `lost election discards buffered dependencies`() {
        // given
        val published = CopyOnWriteArrayList<Event>()
        val eventPublisher = mockk<EventPublisher>()
        every { eventPublisher.publish(any()) } answers {
            published += firstArg<Event>()
        }
        val prioritizer =
            TransactionPrioritizer(
                TransactionPrioritizationProperties().apply {
                    groupMaxSize = 10
                    maxActiveElections = 1_000
                },
                eventPublisher,
                SimpleMeterRegistry(),
            )
        try {
            val dependent = transaction.copy(block = block.copy(sendHash = transaction.hash))
            prioritizer.process(ElectionStarted(account, transaction))
            prioritizer.add(dependent)
            assertEquals(1, prioritizer.getBufferSize())

            // when
            prioritizer.process(ElectionLost(account, transaction))
            prioritizer.add(dependent)

            // then
            awaitCondition { published.filterIsInstance<TransactionReceived>().size == 1 }
            assertEquals(0, prioritizer.getBufferSize())
            assertEquals(
                listOf(dependent.hash),
                published.filterIsInstance<TransactionReceived>().map { it.transaction.hash },
            )
        } finally {
            prioritizer.stop()
        }
    }

    @Test
    fun `releasing election capacity resumes queued transactions`() {
        // Given
        val published = CopyOnWriteArrayList<Event>()
        val eventPublisher = mockk<EventPublisher>()
        every { eventPublisher.publish(any()) } answers {
            published += firstArg<Event>()
        }
        val prioritizer =
            TransactionPrioritizer(
                TransactionPrioritizationProperties().apply {
                    groupMaxSize = 10
                    maxActiveElections = 1
                },
                eventPublisher,
                SimpleMeterRegistry(),
            )

        try {
            prioritizer.process(ElectionStarted(account, transaction))
            prioritizer.add(transaction)
            await()
                .during(100, TimeUnit.MILLISECONDS)
                .atMost(1, TimeUnit.SECONDS)
                .until { prioritizer.getQueueSize() == 1 && published.isEmpty() }

            // When
            prioritizer.process(ElectionLost(account, transaction))

            // Then
            awaitCondition { published.filterIsInstance<TransactionReceived>().size == 1 }
            assertEquals(0, prioritizer.getQueueSize())
        } finally {
            prioritizer.stop()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        await()
            .atMost(5, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.MILLISECONDS)
            .until(condition)
    }
}
