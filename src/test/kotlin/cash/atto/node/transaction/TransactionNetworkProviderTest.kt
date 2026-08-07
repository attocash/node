package cash.atto.node.transaction

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnected
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionStreamRequest
import cash.atto.protocol.NodeFeature
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.atomic.AtomicInteger

class TransactionNetworkProviderTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `serves one transaction stream at a time`() =
        runTest {
            // given
            val repository = mockk<TransactionRepository>()
            val active = AtomicInteger()
            val maximumActive = AtomicInteger()
            val started = AtomicInteger()
            val releases = Channel<Unit>(Channel.UNLIMITED)
            coEvery { repository.findDesc(any(), any(), any()) } answers {
                flow {
                    val activeCount = active.incrementAndGet()
                    maximumActive.updateAndGet { maxOf(it, activeCount) }
                    started.incrementAndGet()
                    releases.receive()
                    active.decrementAndGet()
                }
            }
            val fixture = fixture(repository)

            // when
            val first = launch { fixture.provider.stream(fixture.request(publicKey(1))) }
            val second = launch { fixture.provider.stream(fixture.request(publicKey(2))) }
            runCurrent()

            // then
            assertEquals(1, started.get())
            assertEquals(1, maximumActive.get())

            // when
            releases.send(Unit)
            runCurrent()

            // then
            assertEquals(2, started.get())
            assertEquals(1, maximumActive.get())

            // when
            releases.send(Unit)
            advanceUntilIdle()

            // then
            first.join()
            second.join()
        }

    @Test
    fun `materializes a transaction stream before publishing responses`() =
        runTest {
            // given
            val repository = mockk<TransactionRepository>()
            val transaction = transaction(publicKey(1))
            val collected = CompletableDeferred<Unit>()
            coEvery { repository.findDesc(any(), any(), any()) } returns
                flow {
                    try {
                        emit(transaction)
                    } finally {
                        collected.complete(Unit)
                    }
                }
            val publisher = mockk<NetworkMessagePublisher>()
            every { publisher.publish(any()) } answers {
                assertTrue(collected.isCompleted)
            }
            val fixture = fixture(repository, publisher)

            // when
            fixture.provider.stream(fixture.request(transaction.publicKey))

            // then
            assertTrue(collected.isCompleted)
        }

    @Test
    fun `skips a transaction stream that expired before processing`() =
        runTest {
            // given
            val repository = mockk<TransactionRepository>()
            val clock = MutableClock()
            val fixture = fixture(repository, clock = clock)
            val request =
                fixture.request(
                    publicKey = publicKey(1),
                    timestamp = clock.instant().minus(AttoTransactionStreamRequest.TIMEOUT),
                )

            // when
            fixture.provider.stream(request)

            // then
            coVerify(exactly = 0) { repository.findDesc(any(), any(), any()) }
        }

    @Test
    fun `skips responses when a transaction stream expires while loading`() =
        runTest {
            // given
            val repository = mockk<TransactionRepository>()
            val publisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val clock = MutableClock()
            val transaction = transaction(publicKey(1))
            coEvery { repository.findDesc(any(), any(), any()) } returns
                flow {
                    emit(transaction)
                    clock.advance(AttoTransactionStreamRequest.TIMEOUT)
                }
            val fixture = fixture(repository, publisher, clock)

            // when
            fixture.provider.stream(
                fixture.request(
                    publicKey = transaction.publicKey,
                    timestamp = clock.instant(),
                ),
            )

            // then
            verify(exactly = 0) { publisher.publish(any()) }
        }

    private fun fixture(
        repository: TransactionRepository,
        publisher: NetworkMessagePublisher = mockk(relaxed = true),
        clock: Clock = Clock.systemUTC(),
    ): Fixture {
        val thisNode = node(100)
        val peer = node(101)
        val provider = TransactionNetworkProvider(thisNode, repository, publisher, clock)
        provider.add(NodeConnected(socketAddress(peer), peer))
        return Fixture(provider, peer)
    }

    private data class Fixture(
        val provider: TransactionNetworkProvider,
        val peer: AttoNode,
    ) {
        fun request(
            publicKey: AttoPublicKey,
            timestamp: Instant = Instant.now(),
        ): InboundNetworkMessage<AttoTransactionStreamRequest> =
            InboundNetworkMessage(
                source = MessageSource.WEBSOCKET,
                publicUri = peer.publicUri,
                socketAddress = socketAddress(peer),
                payload =
                    AttoTransactionStreamRequest(
                        publicKey,
                        1U.toAttoHeight(),
                        1U.toAttoHeight(),
                    ),
                timestamp = timestamp,
            )
    }

    private class MutableClock(
        private var now: Instant = Instant.parse("2026-08-07T00:00:00Z"),
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    private companion object {
        fun node(marker: Byte): AttoNode =
            AttoNode(
                network = AttoNetwork.LOCAL,
                protocolVersion = AttoNode.CURRENT_PROTOCOL_VERSION,
                algorithm = AttoAlgorithm.V1,
                publicKey = publicKey(marker),
                publicUri = URI("ws://127.0.0.1:${8_000 + marker}"),
                features = setOf(NodeFeature.HISTORICAL),
            )

        fun socketAddress(node: AttoNode): InetSocketAddress = InetSocketAddress(node.publicUri.host, node.publicUri.port)

        fun transaction(publicKey: AttoPublicKey): Transaction {
            val block =
                AttoSendBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = publicKey,
                    height = 1U.toAttoHeight(),
                    balance = AttoAmount.MAX,
                    timestamp = AttoInstant.now(),
                    previous = AttoHash(ByteArray(32)),
                    receiverAlgorithm = AttoAlgorithm.V1,
                    receiverPublicKey = publicKey(102),
                    amount = AttoAmount.MAX,
                )
            return Transaction(
                block = block,
                signature = AttoSignature(ByteArray(64) { 1 }),
                work = AttoWork(ByteArray(8) { 2 }),
            )
        }

        fun publicKey(marker: Byte): AttoPublicKey = AttoPublicKey(ByteArray(32) { marker })
    }
}
