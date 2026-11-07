package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoTransaction
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionRepository
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.NodeConnectionManager
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRepository
import cash.atto.protocol.AttoBootstrapTransactionPush
import cash.atto.protocol.AttoNode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BootstrapHeadBroadcasterTest {
    @Test
    fun `non historical node does not query or broadcast heads`() =
        runTest {
            // Given
            val fixture = fixture(historical = false)

            // When
            fixture.broadcaster.broadcastSample()

            // Then
            coVerify(exactly = 0) { fixture.uncheckedRepository.count() }
            coVerify(exactly = 0) { fixture.transactionRepository.getLastSample(any()) }
            coVerify(exactly = 0) { fixture.connections.send(any<BroadcastNetworkMessage<*>>()) }
        }

    @Test
    fun `unchecked backlog prevents a head sample query`() =
        runTest {
            // Given
            val fixture = fixture(uncheckedCount = 1)

            // When
            fixture.broadcaster.broadcastSample()

            // Then
            coVerify(exactly = 1) { fixture.uncheckedRepository.count() }
            coVerify(exactly = 0) { fixture.transactionRepository.getLastSample(any()) }
            coVerify(exactly = 0) { fixture.connections.send(any<BroadcastNetworkMessage<*>>()) }
        }

    @Test
    fun `historical node broadcasts each sampled head to everyone`() =
        runTest {
            // Given
            val fixture = fixture()
            val transactions = listOf(mockk<AttoTransaction>(), mockk<AttoTransaction>())
            val sample = transactions.map { transaction -> mockk<Transaction> { every { toAttoTransaction() } returns transaction } }
            coEvery { fixture.transactionRepository.getLastSample(10) } returns flowOf(*sample.toTypedArray())
            val messages = mutableListOf<BroadcastNetworkMessage<*>>()
            coEvery { fixture.connections.send(any<BroadcastNetworkMessage<*>>()) } coAnswers {
                messages += firstArg<BroadcastNetworkMessage<*>>()
            }

            // When
            fixture.broadcaster.broadcastSample()

            // Then
            assertEquals(transactions, messages.map { (it.payload as AttoBootstrapTransactionPush).transaction })
            assertEquals(listOf(BroadcastStrategy.EVERYONE, BroadcastStrategy.EVERYONE), messages.map { it.strategy })
            assertTrue(messages.all { it.exceptions.isEmpty() })
            coVerify(exactly = 1) { fixture.transactionRepository.getLastSample(10) }
        }

    @Test
    fun `broadcast awaits sending and serializes overlapping sample requests`() =
        runTest {
            // Given
            val fixture = fixture()
            val transaction = mockk<Transaction> { every { toAttoTransaction() } returns mockk() }
            coEvery { fixture.transactionRepository.getLastSample(10) } returns flowOf(transaction)
            val sendStarted = CompletableDeferred<Unit>()
            val releaseSend = CompletableDeferred<Unit>()
            coEvery { fixture.connections.send(any<BroadcastNetworkMessage<*>>()) } coAnswers {
                sendStarted.complete(Unit)
                releaseSend.await()
            }

            // When
            val first = async { fixture.broadcaster.broadcastSample() }
            sendStarted.await()
            val second = async { fixture.broadcaster.broadcastSample() }
            runCurrent()

            // Then
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            coVerify(exactly = 1) { fixture.transactionRepository.getLastSample(10) }

            // When
            releaseSend.complete(Unit)
            first.await()
            second.await()

            // Then
            coVerify(exactly = 2) { fixture.transactionRepository.getLastSample(10) }
            coVerify(exactly = 2) { fixture.connections.send(any<BroadcastNetworkMessage<*>>()) }
        }

    private fun fixture(
        historical: Boolean = true,
        uncheckedCount: Long = 0,
    ): Fixture {
        val node = mockk<AttoNode> { every { isNotHistorical() } returns !historical }
        val transactionRepository = mockk<TransactionRepository>()
        val uncheckedRepository = mockk<UncheckedTransactionRepository>()
        coEvery { uncheckedRepository.count() } returns uncheckedCount
        val connections = mockk<NodeConnectionManager>()
        return Fixture(
            BootstrapHeadBroadcaster(node, transactionRepository, uncheckedRepository, connections),
            transactionRepository,
            uncheckedRepository,
            connections,
        )
    }

    private data class Fixture(
        val broadcaster: BootstrapHeadBroadcaster,
        val transactionRepository: TransactionRepository,
        val uncheckedRepository: UncheckedTransactionRepository,
        val connections: NodeConnectionManager,
    )
}
