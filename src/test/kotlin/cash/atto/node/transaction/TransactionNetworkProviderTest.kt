package cash.atto.node.transaction

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoTransaction
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionRequest
import cash.atto.protocol.AttoTransactionStreamRequest
import cash.atto.protocol.NodeFeature
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionNetworkProviderTest {
    @Test
    fun `drops newest request when one active and one thousand are waiting`() =
        runTest {
            // given
            val fixture = fixture()
            val uri = URI("ws://127.0.0.1:9001")
            fixture.connect(uri)
            val activeHash = hash(1)
            val rejectedHash = hash(2_000)
            val activeGate = CompletableDeferred<Unit>()
            coEvery { fixture.repository.findById(any()) } returns null
            coEvery { fixture.repository.findById(activeHash) } coAnswers {
                activeGate.await()
                null
            }
            coEvery { fixture.repository.findDesc(any(), any(), any()) } returns emptyFlow()
            val activeJob = launch { fixture.provider.find(lookup(uri, activeHash)) }
            runCurrent()
            val waitingJobs =
                List(TransactionNetworkProvider.MAX_PENDING_REQUESTS) { index ->
                    launch {
                        if (index % 2 == 0) {
                            fixture.provider.find(lookup(uri, hash(index + 10)))
                        } else {
                            fixture.provider.stream(stream(uri, 1UL, 1UL))
                        }
                    }
                }
            runCurrent()

            // when
            fixture.provider.find(lookup(uri, rejectedHash))

            // then
            coVerify(exactly = 0) { fixture.repository.findById(rejectedHash) }
            verify(exactly = 0) { fixture.publisher.publish(any()) }
            assertFalse(activeJob.isCompleted)
            assertTrue(waitingJobs.none { it.isCompleted })

            // cleanup
            waitingJobs.forEach { it.cancel() }
            activeGate.complete(Unit)
            (waitingJobs + activeJob).joinAll()
        }

    @Test
    fun `concurrent mixed admission is bounded and repository work stays serialized`() =
        runTest {
            // given
            val fixture = fixture()
            val uri = URI("ws://127.0.0.1:9002")
            fixture.connect(uri)
            val activeHash = hash(1)
            val activeGate = CompletableDeferred<Unit>()
            val repositoryCalls = AtomicInteger()
            val activeRepositoryCalls = AtomicInteger()
            val maximumRepositoryCalls = AtomicInteger()
            coEvery { fixture.repository.findById(any()) } coAnswers {
                repositoryCalls.incrementAndGet()
                val current = activeRepositoryCalls.incrementAndGet()
                maximumRepositoryCalls.accumulateAndGet(current, ::max)
                try {
                    if (firstArg<AttoHash>() == activeHash) {
                        activeGate.await()
                    }
                    null
                } finally {
                    activeRepositoryCalls.decrementAndGet()
                }
            }
            coEvery { fixture.repository.findDesc(any(), any(), any()) } coAnswers {
                repositoryCalls.incrementAndGet()
                val current = activeRepositoryCalls.incrementAndGet()
                maximumRepositoryCalls.accumulateAndGet(current, ::max)
                activeRepositoryCalls.decrementAndGet()
                emptyFlow()
            }

            // when
            val jobs =
                List(1_050) { index ->
                    launch {
                        if (index % 2 == 0) {
                            fixture.provider.find(lookup(uri, hash(index + 1)))
                        } else {
                            fixture.provider.stream(stream(uri, 1UL, 1UL))
                        }
                    }
                }
            runCurrent()

            // then
            assertEquals(49, jobs.count { it.isCompleted })
            assertEquals(1, repositoryCalls.get())
            assertEquals(1, maximumRepositoryCalls.get())

            // when
            activeGate.complete(Unit)
            advanceUntilIdle()

            // then
            assertTrue(jobs.all { it.isCompleted })
            assertEquals(TransactionNetworkProvider.MAX_ADMITTED_REQUESTS, repositoryCalls.get())
            assertEquals(1, maximumRepositoryCalls.get())
        }

    @Test
    fun `timeout covers mutex waiting and cooperative processing and permits are reusable`() =
        runTest {
            // given
            val fixture = fixture()
            val uri = URI("ws://127.0.0.1:9003")
            fixture.connect(uri)
            val activeHash = hash(1)
            val waitingHash = hash(2)
            val reusedHash = hash(3)
            val activeGate = CompletableDeferred<Unit>()
            coEvery { fixture.repository.findById(any()) } returns null
            coEvery { fixture.repository.findById(activeHash) } coAnswers {
                activeGate.await()
                null
            }
            val activeJob = launch { fixture.provider.find(lookup(uri, activeHash)) }
            runCurrent()
            val waitingJob = launch { fixture.provider.find(lookup(uri, waitingHash)) }
            runCurrent()

            // when
            advanceTimeBy(60_000)
            runCurrent()

            // then
            assertTrue(activeJob.isCompleted)
            assertTrue(waitingJob.isCompleted)
            coVerify(exactly = 0) { fixture.repository.findById(waitingHash) }
            fixture.provider.find(lookup(uri, reusedHash))
            coVerify(exactly = 1) { fixture.repository.findById(reusedHash) }

            // given
            val transaction = mockk<Transaction>()
            every { transaction.toAttoTransaction() } returns mockk<AttoTransaction>(relaxed = true)
            coEvery { fixture.repository.findDesc(any(), any(), any()) } returns
                flow {
                    delay(61.seconds)
                    emit(transaction)
                }
            val slowStream = launch { fixture.provider.stream(stream(uri, 1UL, 1UL)) }
            runCurrent()

            // when
            advanceTimeBy(60_000)
            runCurrent()

            // then
            assertTrue(slowStream.isCompleted)
            verify(exactly = 0) { fixture.publisher.publish(any()) }
            fixture.provider.find(lookup(uri, hash(4)))
            coVerify(exactly = 1) { fixture.repository.findById(hash(4)) }
        }

    @Test
    fun `failure and cancellation release permits`() =
        runTest {
            // given
            val fixture = fixture()
            val uri = URI("ws://127.0.0.1:9004")
            fixture.connect(uri)
            val repositoryFailureHash = hash(1)
            val publisherFailureHash = hash(2)
            val cancelledHash = hash(3)
            val transaction = mockk<Transaction>()
            every { transaction.toAttoTransaction() } returns mockk<AttoTransaction>(relaxed = true)
            coEvery { fixture.repository.findById(any()) } returns null
            coEvery { fixture.repository.findById(repositoryFailureHash) } throws IllegalStateException("repository failed")
            coEvery { fixture.repository.findById(publisherFailureHash) } returns transaction

            // when
            val repositoryFailure = failureOf { fixture.provider.find(lookup(uri, repositoryFailureHash)) }
            every { fixture.publisher.publish(any()) } throws IllegalStateException("publication failed")
            val publisherFailure = failureOf { fixture.provider.find(lookup(uri, publisherFailureHash)) }
            every { fixture.publisher.publish(any()) } returns Unit
            val cancellationGate = CompletableDeferred<Unit>()
            coEvery { fixture.repository.findById(cancelledHash) } coAnswers {
                cancellationGate.await()
                null
            }
            val cancelledJob = launch { fixture.provider.find(lookup(uri, cancelledHash)) }
            runCurrent()
            cancelledJob.cancelAndJoin()

            // then
            assertTrue(repositoryFailure is IllegalStateException)
            assertTrue(publisherFailure is IllegalStateException)

            // when
            val saturationGate = CompletableDeferred<Unit>()
            val saturationHashes =
                List(TransactionNetworkProvider.MAX_ADMITTED_REQUESTS) { index -> hash(index + 10_000) }.toSet()
            coEvery { fixture.repository.findById(match { it in saturationHashes }) } coAnswers {
                saturationGate.await()
                null
            }
            val saturationJobs =
                saturationHashes.map { saturationHash ->
                    launch { fixture.provider.find(lookup(uri, saturationHash)) }
                }
            runCurrent()

            // then
            assertEquals(TransactionNetworkProvider.MAX_ADMITTED_REQUESTS, saturationJobs.size)
            assertTrue(saturationJobs.none { it.isCompleted })

            // cleanup
            saturationJobs.forEach { it.cancel() }
            saturationJobs.joinAll()
        }

    @Test
    fun `maximum stream publishes one thousand responses with ten seconds of pacing`() =
        runTest {
            // given
            val fixture = fixture()
            val uri = URI("ws://127.0.0.1:9005")
            fixture.connect(uri)
            val transaction = mockk<Transaction>()
            every { transaction.toAttoTransaction() } returns mockk<AttoTransaction>(relaxed = true)
            coEvery { fixture.repository.findDesc(any(), any(), any()) } returns
                List(1_000) { transaction }.asFlow()
            val startedAt = testScheduler.currentTime

            // when
            fixture.provider.stream(stream(uri, 1UL, 1_000UL))

            // then
            coVerify(exactly = 1) { fixture.repository.findDesc(any(), any(), any()) }
            verify(exactly = 1_000) { fixture.publisher.publish(any()) }
            assertEquals(10_000, testScheduler.currentTime - startedAt)
        }

    @Test
    fun `disconnect prevents repository access and stops stream responses`() =
        runTest {
            // given
            val fixture = fixture()
            val uri = URI("ws://127.0.0.1:9006")
            val disconnectedHash = hash(1)
            coEvery { fixture.repository.findById(any()) } returns null

            // when
            fixture.provider.find(lookup(uri, disconnectedHash))

            // then
            coVerify(exactly = 0) { fixture.repository.findById(disconnectedHash) }

            // given
            fixture.connect(uri)
            val transaction = mockk<Transaction>()
            every { transaction.toAttoTransaction() } returns mockk<AttoTransaction>(relaxed = true)
            coEvery { fixture.repository.findDesc(any(), any(), any()) } returns
                List(3) { transaction }.asFlow()
            val published = AtomicInteger()
            every { fixture.publisher.publish(any()) } answers {
                if (published.incrementAndGet() == 1) {
                    fixture.disconnect(uri)
                }
            }

            // when
            fixture.provider.stream(stream(uri, 1UL, 3UL))

            // then
            coVerify(exactly = 1) { fixture.repository.findDesc(any(), any(), any()) }
            verify(exactly = 1) { fixture.publisher.publish(any()) }
        }

    private fun fixture(): Fixture {
        val repository = mockk<TransactionRepository>()
        val publisher = mockk<NetworkMessagePublisher>(relaxed = true)
        val provider =
            TransactionNetworkProvider(
                thisNode = sampleNode(URI("ws://127.0.0.1:8000"), historical = true),
                transactionRepository = repository,
                networkMessagePublisher = publisher,
            )
        return Fixture(provider, repository, publisher)
    }

    private fun lookup(
        uri: URI,
        hash: AttoHash,
    ): InboundNetworkMessage<AttoTransactionRequest> =
        InboundNetworkMessage(
            MessageSource.WEBSOCKET,
            uri,
            socketAddress(),
            AttoTransactionRequest(hash),
        )

    private fun stream(
        uri: URI,
        start: ULong,
        end: ULong,
    ): InboundNetworkMessage<AttoTransactionStreamRequest> =
        InboundNetworkMessage(
            MessageSource.WEBSOCKET,
            uri,
            socketAddress(),
            AttoTransactionStreamRequest(
                AttoPublicKey(ByteArray(32) { 1 }),
                AttoHeight(start),
                AttoHeight(end),
            ),
        )

    private fun hash(seed: Int): AttoHash =
        AttoHash(
            ByteArray(32).also { bytes ->
                ByteBuffer.wrap(bytes).putInt(seed)
            },
        )

    private fun socketAddress(): InetSocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 8_000)

    private fun sampleNode(
        uri: URI,
        historical: Boolean,
    ): AttoNode =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(ByteArray(32) { uri.port.toByte() }),
            publicUri = uri,
            features = if (historical) setOf(NodeFeature.HISTORICAL) else emptySet(),
        )

    private suspend fun failureOf(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (throwable: Throwable) {
            throwable
        }

    private inner class Fixture(
        val provider: TransactionNetworkProvider,
        val repository: TransactionRepository,
        val publisher: NetworkMessagePublisher,
    ) {
        fun connect(uri: URI) {
            provider.add(NodeConnected(socketAddress(), sampleNode(uri, historical = true)))
        }

        fun disconnect(uri: URI) {
            provider.remove(NodeDisconnected(socketAddress(), sampleNode(uri, historical = true)))
        }
    }
}
