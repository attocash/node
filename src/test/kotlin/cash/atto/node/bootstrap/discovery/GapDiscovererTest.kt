package cash.atto.node.bootstrap.discovery

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
import cash.atto.node.Event
import cash.atto.node.EventPublisher
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.bootstrap.unchecked.GapView
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionRepository
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnected
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionStreamResponse
import cash.atto.protocol.NodeFeature
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class GapDiscovererTest {
    @Test
    fun `wrong source cannot advance or populate recovery state`() =
        runBlocking {
            // given
            val chain = transactionChain()
            val fixture = Fixture(chain)
            fixture.discoverer.resolve()

            // when
            fixture.respond(WRONG_PEER, chain[2])
            fixture.respond(WRONG_PEER, chain[0])

            // then
            assertEquals(emptyList<AttoTransaction>(), fixture.discoveredTransactions)

            // when
            fixture.respond(SELECTED_PEER, chain[2])
            fixture.respond(SELECTED_PEER, chain[1])

            // then
            assertEquals(listOf(chain[2], chain[1]), fixture.discoveredTransactions)

            // when
            fixture.respond(SELECTED_PEER, chain[0])

            // then
            assertEquals(listOf(chain[2], chain[1], chain[0]), fixture.discoveredTransactions)
        }

    @Test
    fun `replayed stale and mismatched responses preserve the active pointer`() =
        runBlocking {
            // given
            val chain = transactionChain()
            val fixture = Fixture(chain)
            fixture.discoverer.resolve()
            fixture.respond(SELECTED_PEER, chain[2])
            val mismatched = transaction(chain[1].block.publicKey, 3UL, chain[0].hash, 30)
            val outOfRange = transaction(chain[1].block.publicKey, 1UL, chain[0].hash, 10)

            // when
            fixture.respond(SELECTED_PEER, chain[2])
            fixture.respond(SELECTED_PEER, mismatched)
            fixture.respond(SELECTED_PEER, outOfRange)

            // then
            assertEquals(listOf(chain[2]), fixture.discoveredTransactions)

            // when
            fixture.respond(SELECTED_PEER, chain[1])
            fixture.respond(SELECTED_PEER, chain[0])

            // then
            assertEquals(listOf(chain[2], chain[1], chain[0]), fixture.discoveredTransactions)
        }

    @Test
    fun `interior replay does not extend the fixed request deadline`() =
        runBlocking {
            // given
            val chain = transactionChain()
            val clock = MutableClock()
            val fixture = Fixture(chain, clock)
            fixture.discoverer.resolve()
            fixture.respond(SELECTED_PEER, chain[0])
            clock.advance(Duration.ofSeconds(59))

            // when
            fixture.respond(SELECTED_PEER, chain[0])
            clock.advance(Duration.ofSeconds(2))
            fixture.respond(SELECTED_PEER, chain[2])

            // then
            assertEquals(emptyList<AttoTransaction>(), fixture.discoveredTransactions)
            assertEquals(1, fixture.directMessages.size)

            // when
            fixture.discoverer.resolve()
            fixture.respond(SELECTED_PEER, chain[2])

            // then
            assertEquals(2, fixture.directMessages.size)
            assertEquals(listOf(chain[2]), fixture.discoveredTransactions)
        }

    @Test
    fun `selected peer out of order sequence drains once and completes`() =
        runBlocking {
            // given
            val chain = transactionChain()
            val fixture = Fixture(chain)
            fixture.discoverer.resolve()

            // when
            fixture.respond(SELECTED_PEER, chain[0])
            fixture.respond(SELECTED_PEER, chain[1])
            fixture.respond(SELECTED_PEER, chain[2])

            // then
            assertEquals(listOf(chain[2], chain[1], chain[0]), fixture.discoveredTransactions)
        }

    @Test
    fun `out of order buffer is capped by requested range size`() =
        runBlocking {
            // given
            val chain = transactionChain(1_000)
            val fixture = Fixture(chain)
            fixture.discoverer.resolve()
            repeat(chain.size) { index ->
                val height = 2UL + (index % (chain.size - 1)).toULong()
                fixture.respond(
                    SELECTED_PEER,
                    transaction(chain[0].block.publicKey, height, chain[0].hash, 10_000 + index),
                )
            }
            fixture.respond(SELECTED_PEER, chain[chain.lastIndex - 1])

            // when
            fixture.respond(SELECTED_PEER, chain.last())

            // then
            assertEquals(listOf(chain.last()), fixture.discoveredTransactions)

            // when
            chain.dropLast(1).asReversed().forEach { fixture.respond(SELECTED_PEER, it) }

            // then
            assertEquals(chain.asReversed(), fixture.discoveredTransactions)
        }

    @Test
    fun `duplicate buffered hashes do not consume additional capacity`() =
        runBlocking {
            // given
            val chain = transactionChain()
            val fixture = Fixture(chain)
            val duplicate = transaction(chain[0].block.publicKey, 2UL, chain[0].hash, 20)
            fixture.discoverer.resolve()

            // when
            repeat(3) { fixture.respond(SELECTED_PEER, duplicate) }
            fixture.respond(SELECTED_PEER, chain[0])
            fixture.respond(SELECTED_PEER, chain[1])
            fixture.respond(SELECTED_PEER, chain[2])

            // then
            assertEquals(listOf(chain[2], chain[1], chain[0]), fixture.discoveredTransactions)
        }

    @Test
    fun `completion excludes rediscovery while concurrent exact responses remain exactly once`() =
        runBlocking {
            // given
            val chain = transactionChain()
            val completionPublishStarted = CountDownLatch(1)
            val releaseCompletionPublish = CountDownLatch(1)
            val fixture =
                Fixture(chain) { transaction ->
                    if (transaction == chain[2]) {
                        completionPublishStarted.countDown()
                        check(releaseCompletionPublish.await(5, TimeUnit.SECONDS))
                    }
                }
            fixture.discoverer.resolve()
            fixture.respond(SELECTED_PEER, chain[0])
            fixture.respond(SELECTED_PEER, chain[1])
            val executor = Executors.newFixedThreadPool(3)

            try {
                // when
                val completingResponse = executor.submit { fixture.respond(SELECTED_PEER, chain[2]) }
                assertTrue(completionPublishStarted.await(5, TimeUnit.SECONDS))
                val duplicateResponse = executor.submit { fixture.respond(SELECTED_PEER, chain[2]) }
                val concurrentResolve = executor.submit { runBlocking { fixture.discoverer.resolve() } }
                duplicateResponse.get(5, TimeUnit.SECONDS)
                concurrentResolve.get(5, TimeUnit.SECONDS)

                // then
                assertEquals(1, fixture.directMessages.size)

                // when
                releaseCompletionPublish.countDown()
                completingResponse.get(5, TimeUnit.SECONDS)

                // then
                assertEquals(chain.asReversed(), fixture.discoveredTransactions)
                assertEquals(1, fixture.directMessages.size)
            } finally {
                releaseCompletionPublish.countDown()
                executor.shutdownNow()
            }
        }

    private class Fixture(
        chain: List<AttoTransaction>,
        clock: Clock = MutableClock(),
        private val beforeTransactionPublished: (AttoTransaction) -> Unit = {},
    ) {
        private val repository = mockk<UncheckedTransactionRepository>()
        private val networkMessagePublisher = mockk<NetworkMessagePublisher>()
        private val eventPublisher = mockk<EventPublisher>()

        val directMessages = Collections.synchronizedList(mutableListOf<DirectNetworkMessage<*>>())
        val discoveredTransactions = Collections.synchronizedList(mutableListOf<AttoTransaction>())
        val discoverer = GapDiscoverer(repository, networkMessagePublisher, eventPublisher, clock)

        init {
            val publicKey = chain.first().block.publicKey
            val gap =
                GapView(
                    publicKey = publicKey,
                    startHeight = chain.first().block.height,
                    endHeight = chain.last().block.height,
                    expectedEndHash = chain.last().hash,
                )
            coEvery { repository.findGaps(any(), any()) } answers {
                if (publicKey in firstArg<Collection<AttoPublicKey>>()) {
                    emptyFlow()
                } else {
                    flowOf(gap)
                }
            }
            every { networkMessagePublisher.publish(any()) } answers {
                val message = firstArg<NetworkMessage<*>>()
                if (message is DirectNetworkMessage<*>) {
                    directMessages.add(message)
                }
            }
            every { eventPublisher.publish(any()) } answers {
                val event = firstArg<Event>()
                if (event is TransactionDiscovered) {
                    val transaction = event.transaction.toAttoTransaction()
                    beforeTransactionPublished(transaction)
                    discoveredTransactions.add(transaction)
                }
            }
            discoverer.add(
                NodeConnected(
                    connectionSocketAddress = SOCKET_ADDRESS,
                    node =
                        AttoNode(
                            network = AttoNetwork.LOCAL,
                            protocolVersion = 0u,
                            algorithm = AttoAlgorithm.V1,
                            publicKey = AttoPublicKey(ByteArray(32) { 9 }),
                            publicUri = SELECTED_PEER,
                            features = setOf(NodeFeature.HISTORICAL),
                        ),
                ),
            )
        }

        fun respond(
            peer: URI,
            transaction: AttoTransaction,
        ) {
            discoverer.process(
                InboundNetworkMessage(
                    source = MessageSource.WEBSOCKET,
                    publicUri = peer,
                    socketAddress = SOCKET_ADDRESS,
                    payload = AttoTransactionStreamResponse(transaction),
                ),
            )
        }
    }

    private class MutableClock(
        private var current: Instant = Instant.parse("2026-07-10T00:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    companion object {
        private val SELECTED_PEER = URI("ws://selected-peer:8080")
        private val WRONG_PEER = URI("ws://wrong-peer:8080")
        private val SOCKET_ADDRESS = InetSocketAddress("127.0.0.1", 8080)

        private fun transactionChain(size: Int = 3): List<AttoTransaction> {
            require(size > 0)
            val publicKey = AttoPublicKey(ByteArray(32) { 1 })
            val transactions = mutableListOf<AttoTransaction>()
            var previous = AttoHash(ByteArray(32))
            for (height in 2UL..(size.toULong() + 1UL)) {
                val transaction = transaction(publicKey, height, previous, height.toInt())
                transactions.add(transaction)
                previous = transaction.hash
            }
            return transactions
        }

        private fun transaction(
            publicKey: AttoPublicKey,
            height: ULong,
            previous: AttoHash,
            discriminator: Int,
        ): AttoTransaction {
            val block =
                AttoReceiveBlock(
                    network = AttoNetwork.LOCAL,
                    version = 0U.toAttoVersion(),
                    algorithm = AttoAlgorithm.V1,
                    publicKey = publicKey,
                    height = height.toAttoHeight(),
                    balance = AttoAmount(100u),
                    timestamp = Instant.ofEpochSecond(discriminator.toLong()).toAtto(),
                    previous = previous,
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = AttoHash(ByteArray(32) { discriminator.toByte() }),
                )
            return AttoTransaction(
                block = block,
                signature = AttoSignature(ByteArray(64) { discriminator.toByte() }),
                work = AttoWork(ByteArray(8) { discriminator.toByte() }),
            )
        }
    }
}
