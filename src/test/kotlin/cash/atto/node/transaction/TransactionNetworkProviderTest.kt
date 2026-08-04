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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.atomic.AtomicInteger

class TransactionNetworkProviderTest {
    @Test
    fun `serves transaction streams concurrently`() =
        runTest {
            // given
            val repository = mockk<TransactionRepository>()
            val active = AtomicInteger()
            val bothStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { repository.findDesc(any(), any(), any()) } answers {
                flow {
                    if (active.incrementAndGet() == 2) {
                        bothStarted.complete(Unit)
                    }
                    release.await()
                    active.decrementAndGet()
                }
            }
            val fixture = fixture(repository)

            // when
            val first = launch { fixture.provider.stream(fixture.request(publicKey(1))) }
            val second = launch { fixture.provider.stream(fixture.request(publicKey(2))) }
            runCurrent()

            // then
            assertTrue(bothStarted.isCompleted)

            release.complete(Unit)
            first.join()
            second.join()
        }

    @Test
    fun `limits concurrent transaction streams to the protocol maximum`() =
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
            val jobs =
                (1..AttoTransactionStreamRequest.MAX_PARALLEL_STREAMS + 1).map { marker ->
                    launch { fixture.provider.stream(fixture.request(publicKey(marker.toByte()))) }
                }

            // when
            runCurrent()

            // then
            assertEquals(AttoTransactionStreamRequest.MAX_PARALLEL_STREAMS, started.get())
            assertEquals(AttoTransactionStreamRequest.MAX_PARALLEL_STREAMS, maximumActive.get())

            repeat(jobs.size) {
                releases.trySend(Unit)
            }
            advanceUntilIdle()
            assertEquals(jobs.size, started.get())
        }

    @Test
    fun `materializes a transaction stream before publishing paced responses`() =
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

    private fun fixture(
        repository: TransactionRepository,
        publisher: NetworkMessagePublisher = mockk(relaxed = true),
    ): Fixture {
        val thisNode = node(100)
        val peer = node(101)
        val provider = TransactionNetworkProvider(thisNode, repository, publisher)
        provider.add(NodeConnected(socketAddress(peer), peer))
        return Fixture(provider, peer)
    }

    private data class Fixture(
        val provider: TransactionNetworkProvider,
        val peer: AttoNode,
    ) {
        fun request(publicKey: AttoPublicKey): InboundNetworkMessage<AttoTransactionStreamRequest> =
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
            )
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
