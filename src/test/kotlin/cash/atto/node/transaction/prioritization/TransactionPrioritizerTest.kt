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
import cash.atto.node.account.AccountUpdated
import cash.atto.node.election.ElectionExpired
import cash.atto.node.election.ElectionStarted
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionDropped
import cash.atto.node.transaction.TransactionReceived
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.TransactionSource
import cash.atto.protocol.AttoTransactionPush
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class TransactionPrioritizerTest {
    private var seed = 1

    @Test
    fun `recoverable rejection allows the same transaction to be queued again`() {
        // given
        val context = createContext()
        val transaction = createTransaction()
        val account = createAccount(transaction.block.publicKey)
        val message = transaction.toInboundMessage()

        // when
        context.prioritizer.add(message)
        context.prioritizer.process()
        context.prioritizer.add(message)
        context.prioritizer.process()
        context.prioritizer.process(
            TransactionRejected(
                TransactionRejectionReason.PREVIOUS_NOT_FOUND,
                "Previous transaction is missing",
                account,
                transaction,
            ),
        )
        context.prioritizer.add(message)
        context.prioritizer.process()

        // then
        assertEquals(
            listOf(transaction.hash, transaction.hash),
            context.published.filterIsInstance<TransactionReceived>().map { it.transaction.hash },
        )
    }

    @Test
    fun `dependency limit drops excess transaction`() {
        // given
        val context = createContext(dependencyMaxSize = 1)
        val candidate = createTransaction()
        val first = createTransaction(sendHash = candidate.hash)
        val excess = createTransaction(sendHash = candidate.hash)
        context.prioritizer.process(ElectionStarted(createAccount(candidate.block.publicKey), candidate))

        // when
        context.prioritizer.add(first.toInboundMessage())
        context.prioritizer.add(excess.toInboundMessage())
        context.prioritizer.add(excess.toInboundMessage())

        // then
        assertEquals(1, context.prioritizer.getBufferSize())
        assertEquals(
            listOf(excess.hash),
            context.published.filterIsInstance<TransactionDropped>().map { it.transaction.hash },
        )
    }

    @Test
    fun `aggregate limit applies across dependencies`() {
        // given
        val context = createContext(dependencyMaxSize = 2, bufferMaxSize = 1)
        val firstCandidate = createTransaction()
        val secondCandidate = createTransaction()
        val first = createTransaction(sendHash = firstCandidate.hash)
        val excess = createTransaction(sendHash = secondCandidate.hash)
        context.prioritizer.process(ElectionStarted(createAccount(firstCandidate.block.publicKey), firstCandidate))
        context.prioritizer.process(ElectionStarted(createAccount(secondCandidate.block.publicKey), secondCandidate))

        // when
        context.prioritizer.add(first)
        context.prioritizer.add(excess)

        // then
        assertEquals(1, context.prioritizer.getBufferSize())
        assertEquals(
            listOf(excess.hash),
            context.published.filterIsInstance<TransactionDropped>().map { it.transaction.hash },
        )
    }

    @Test
    fun `dependency limit remains exact under concurrent registration`() {
        // given
        val transactionCount = 16
        val dependencyLimit = 5
        val context = createContext(dependencyMaxSize = dependencyLimit, bufferMaxSize = transactionCount)
        val candidate = createTransaction()
        val account = createAccount(candidate.block.publicKey)
        val transactions = List(transactionCount) { createTransaction(sendHash = candidate.hash) }
        context.prioritizer.process(ElectionStarted(account, candidate))

        // when
        addConcurrently(context.prioritizer, transactions)

        // then
        assertEquals(dependencyLimit, context.prioritizer.getBufferSize())

        // when
        context.prioritizer.process(AccountUpdated(TransactionSource.ELECTION, account, account, candidate))
        context.prioritizer.process()

        // then
        assertConcurrentOutcomes(context, transactions, dependencyLimit)
        assertEquals(0, context.prioritizer.getBufferSize())
    }

    @Test
    fun `aggregate limit remains exact under concurrent registration across dependencies`() {
        // given
        val dependencyCount = 4
        val transactionsPerDependency = 4
        val transactionCount = dependencyCount * transactionsPerDependency
        val bufferLimit = 7
        val context = createContext(dependencyMaxSize = transactionCount, bufferMaxSize = bufferLimit)
        val elections =
            List(dependencyCount) {
                val candidate = createTransaction()
                candidate to createAccount(candidate.block.publicKey)
            }
        val transactions =
            elections.flatMap { (candidate) ->
                List(transactionsPerDependency) { createTransaction(sendHash = candidate.hash) }
            }
        elections.forEach { (candidate, account) ->
            context.prioritizer.process(ElectionStarted(account, candidate))
        }

        // when
        addConcurrently(context.prioritizer, transactions)

        // then
        assertEquals(bufferLimit, context.prioritizer.getBufferSize())

        // when
        elections.forEach { (candidate, account) ->
            context.prioritizer.process(AccountUpdated(TransactionSource.ELECTION, account, account, candidate))
        }
        context.prioritizer.process()

        // then
        assertConcurrentOutcomes(context, transactions, bufferLimit)
        assertEquals(0, context.prioritizer.getBufferSize())
    }

    @Test
    fun `duplicate buffered transaction consumes one slot`() {
        // given
        val context = createContext(dependencyMaxSize = 1, bufferMaxSize = 1)
        val candidate = createTransaction()
        val dependent = createTransaction(sendHash = candidate.hash)
        context.prioritizer.process(ElectionStarted(createAccount(candidate.block.publicKey), candidate))

        // when
        context.prioritizer.add(dependent)
        context.prioritizer.add(dependent)

        // then
        assertEquals(1, context.prioritizer.getBufferSize())
        assertEquals(emptyList<TransactionDropped>(), context.published.filterIsInstance<TransactionDropped>())
    }

    @Test
    fun `confirmation removes all candidates and requeues only winner dependencies`() {
        // given
        val context = createContext()
        val publicKey = createPublicKey()
        val winner = createTransaction(publicKey = publicKey)
        val loser = createTransaction(publicKey = publicKey, height = winner.block.height)
        val winnerDependent = createTransaction(sendHash = winner.hash)
        val loserDependent = createTransaction(sendHash = loser.hash)
        val account = createAccount(publicKey)
        context.prioritizer.process(ElectionStarted(account, winner))
        context.prioritizer.process(ElectionStarted(account, loser))
        context.prioritizer.add(winnerDependent)
        context.prioritizer.add(loserDependent)

        // when
        context.prioritizer.process(AccountUpdated(TransactionSource.ELECTION, account, account, winner))
        context.prioritizer.process()

        // then
        assertEquals(0, context.prioritizer.getBufferSize())
        assertEquals(
            listOf(winnerDependent.hash),
            context.published.filterIsInstance<TransactionReceived>().map { it.transaction.hash },
        )

        // when
        val postConfirmation = createTransaction(sendHash = loser.hash)
        context.prioritizer.add(postConfirmation)
        context.prioritizer.process()

        // then
        assertEquals(
            listOf(winnerDependent.hash, postConfirmation.hash),
            context.published.filterIsInstance<TransactionReceived>().map { it.transaction.hash },
        )
    }

    @Test
    fun `expiry removes every candidate without requeue`() {
        // given
        val context = createContext()
        val publicKey = createPublicKey()
        val firstCandidate = createTransaction(publicKey = publicKey)
        val secondCandidate = createTransaction(publicKey = publicKey, height = firstCandidate.block.height)
        val firstDependent = createTransaction(sendHash = firstCandidate.hash)
        val secondDependent = createTransaction(sendHash = secondCandidate.hash)
        val account = createAccount(publicKey)
        context.prioritizer.process(ElectionStarted(account, firstCandidate))
        context.prioritizer.process(ElectionStarted(account, secondCandidate))
        context.prioritizer.add(firstDependent)
        context.prioritizer.add(secondDependent)

        // when
        context.prioritizer.process(ElectionExpired(account, firstCandidate))
        context.prioritizer.process()

        // then
        assertEquals(0, context.prioritizer.getBufferSize())
        assertEquals(emptyList<TransactionReceived>(), context.published.filterIsInstance<TransactionReceived>())

        // when
        val postExpiry = createTransaction(sendHash = secondCandidate.hash)
        context.prioritizer.add(postExpiry)
        context.prioritizer.process()

        // then
        assertEquals(
            listOf(postExpiry.hash),
            context.published.filterIsInstance<TransactionReceived>().map { it.transaction.hash },
        )
    }

    @Test
    fun `clear resets dependency gauges and aggregate count`() {
        // given
        val context = createContext()
        val candidate = createTransaction()
        val dependent = createTransaction(sendHash = candidate.hash)
        context.prioritizer.start()
        context.prioritizer.process(ElectionStarted(createAccount(candidate.block.publicKey), candidate))
        context.prioritizer.add(dependent)

        // when
        context.prioritizer.clear()

        // then
        assertEquals(0, context.prioritizer.getBufferSize())
        assertEquals(
            0.0,
            context.meterRegistry
                .find("transactions.prioritizer.pending.dependencies")
                .gauge()!!
                .value(),
        )
        assertEquals(
            0.0,
            context.meterRegistry
                .find("transactions.prioritizer.buffer.size")
                .gauge()!!
                .value(),
        )
    }

    @Test
    fun `dependency bounds must be positive`() {
        // given
        val properties = TransactionPrioritizationProperties()

        // when
        val dependencyFailure = assertThrows(IllegalArgumentException::class.java) { properties.dependencyMaxSize = 0 }
        val bufferFailure = assertThrows(IllegalArgumentException::class.java) { properties.bufferMaxSize = 0 }

        // then
        assertEquals("dependencyMaxSize must be positive", dependencyFailure.message)
        assertEquals("bufferMaxSize must be positive", bufferFailure.message)
    }

    private fun createContext(
        dependencyMaxSize: Int = 10,
        bufferMaxSize: Int = 100,
    ): TestContext {
        val published = CopyOnWriteArrayList<Event>()
        val eventPublisher = mockk<EventPublisher>()
        every { eventPublisher.publish(any()) } answers {
            published += firstArg<Event>()
            Unit
        }
        val meterRegistry = SimpleMeterRegistry()
        val prioritizer =
            TransactionPrioritizer(
                TransactionPrioritizationProperties().apply {
                    groupMaxSize = 10
                    maxActiveElections = 1_000
                    this.dependencyMaxSize = dependencyMaxSize
                    this.bufferMaxSize = bufferMaxSize
                },
                eventPublisher,
                meterRegistry,
            )
        return TestContext(prioritizer, meterRegistry, published)
    }

    private fun addConcurrently(
        prioritizer: TransactionPrioritizer,
        transactions: List<Transaction>,
    ) {
        val startBarrier = CyclicBarrier(transactions.size)
        val executor = Executors.newFixedThreadPool(transactions.size)

        try {
            val futures =
                transactions.map { transaction ->
                    executor.submit {
                        startBarrier.await(5, TimeUnit.SECONDS)
                        prioritizer.add(transaction)
                    }
                }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate")
        }
    }

    private fun assertConcurrentOutcomes(
        context: TestContext,
        transactions: List<Transaction>,
        expectedAcceptedCount: Int,
    ) {
        val submittedHashes = transactions.map { it.hash }
        val acceptedHashes =
            context.published.filterIsInstance<TransactionReceived>().map { it.transaction.hash }
        val droppedHashes =
            context.published.filterIsInstance<TransactionDropped>().map { it.transaction.hash }
        val submittedHashSet = submittedHashes.toSet()
        val acceptedHashSet = acceptedHashes.toSet()
        val droppedHashSet = droppedHashes.toSet()

        assertEquals(submittedHashes.size, submittedHashSet.size, "Submitted transaction hashes must be unique")
        assertEquals(acceptedHashes.size, acceptedHashSet.size, "Accepted transaction hashes must be unique")
        assertEquals(droppedHashes.size, droppedHashSet.size, "Dropped transaction hashes must be unique")
        assertEquals(emptySet<AttoHash>(), acceptedHashSet intersect droppedHashSet)
        assertEquals(submittedHashSet, acceptedHashSet + droppedHashSet)
        assertEquals(expectedAcceptedCount, acceptedHashSet.size)
        assertEquals(transactions.size - expectedAcceptedCount, droppedHashSet.size)
    }

    private fun createTransaction(
        publicKey: AttoPublicKey = createPublicKey(),
        height: AttoHeight = AttoHeight(2UL),
        sendHash: AttoHash = createHash(),
    ): Transaction {
        val block =
            AttoReceiveBlock(
                network = AttoNetwork.LOCAL,
                version = 0U.toAttoVersion(),
                algorithm = AttoAlgorithm.V1,
                publicKey = publicKey,
                height = height,
                balance = AttoAmount(1UL),
                timestamp = AttoInstant.now(),
                previous = createHash(),
                sendHashAlgorithm = AttoAlgorithm.V1,
                sendHash = sendHash,
            )
        return Transaction(
            block = block,
            signature = AttoSignature(createBytes(64)),
            work = AttoWork(createBytes(8)),
        )
    }

    private fun createAccount(publicKey: AttoPublicKey): Account =
        Account(
            publicKey = publicKey,
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            height = 1,
            balance = AttoAmount(1UL),
            lastTransactionTimestamp = AttoInstant.now().toJavaInstant(),
            lastTransactionHash = createHash(),
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = createPublicKey(),
        )

    private fun Transaction.toInboundMessage(): InboundNetworkMessage<AttoTransactionPush> =
        InboundNetworkMessage(
            MessageSource.WEBSOCKET,
            URI("ws://127.0.0.1:8082"),
            InetSocketAddress("127.0.0.1", 8082),
            AttoTransactionPush(toAttoTransaction()),
        )

    private fun createPublicKey() = AttoPublicKey(createBytes(32))

    private fun createHash() = AttoHash(createBytes(32))

    private fun createBytes(size: Int): ByteArray = ByteArray(size) { seed.toByte() }.also { seed++ }

    private data class TestContext(
        val prioritizer: TransactionPrioritizer,
        val meterRegistry: SimpleMeterRegistry,
        val published: List<Event>,
    )
}
