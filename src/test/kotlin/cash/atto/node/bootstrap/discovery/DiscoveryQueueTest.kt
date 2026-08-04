package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.EventPublisher
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.bootstrap.unchecked.UncheckedTransaction
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionService
import cash.atto.node.transaction.Transaction
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryQueueTest {
    @Test
    fun `remaining target capacity is clamped at zero for suspended admissions`() =
        runTest {
            // Given
            val service = mockk<UncheckedTransactionService>(relaxed = true)
            val fixture = fixture(service, properties(capacity = 2, batchSize = 1))
            val events = (20..22).map { event(it.toByte()) }

            // When
            fixture.queue.queue(events[0], DiscoverySource.GAP)
            fixture.queue.queue(events[1], DiscoverySource.GAP)
            val suspended =
                launch {
                    fixture.queue.queue(events[2], DiscoverySource.GAP)
                }
            runCurrent()

            // Then
            assertEquals(0, fixture.queue.remainingTargetCapacity())

            // When
            suspended.cancelAndJoin()
            fixture.worker.flush()

            // Then
            assertEquals(2, fixture.queue.remainingTargetCapacity())
        }

    @Test
    fun `capacity suspends the next admission without blocking unrelated work and preserves FIFO order`() =
        runTest {
            // Given
            val savedHashes = mutableListOf<AttoHash>()
            val service = mockk<UncheckedTransactionService>()
            coEvery { service.save(any()) } answers {
                firstArg<Collection<UncheckedTransaction>>()
                    .also { batch -> savedHashes += batch.map { it.hash } }
                    .size
                    .toLong()
            }
            val fixture = fixture(service, properties(capacity = 2, batchSize = 1))
            val events = (1..3).map { event(it.toByte()) }
            fixture.queue.queue(events[0], DiscoverySource.GAP)
            fixture.queue.queue(events[1], DiscoverySource.GAP)

            // When
            val thirdAdmission =
                async {
                    fixture.queue.queue(events[2], DiscoverySource.GAP)
                }
            var unrelatedWorkCompleted = false
            launch { unrelatedWorkCompleted = true }
            runCurrent()

            // Then
            assertTrue(fixture.queue.isAtCapacity())
            assertFalse(thirdAdmission.isCompleted)
            assertTrue(unrelatedWorkCompleted)
            assertEquals(3.0, fixture.gauge("transactions.discovery.backlog.depth"))
            assertEquals(1.0, fixture.gauge("transactions.discovery.backlog.overshoot"))

            // When
            fixture.worker.flush()
            assertTrue(thirdAdmission.await())
            fixture.worker.flush()

            // Then
            assertEquals(events.map { it.transaction.hash }, savedHashes)
            assertFalse(fixture.queue.isAtCapacity())
            assertEquals(0.0, fixture.gauge("transactions.discovery.backlog.depth"))
            assertEquals(3.0, fixture.counter("transactions.discovery.persisted", "source", "gap"))
        }

    @Test
    fun `headroom accepts in flight replies above the discovery target before suspending`() =
        runTest {
            // Given
            val service = mockk<UncheckedTransactionService>()
            coEvery { service.save(any()) } answers {
                firstArg<Collection<UncheckedTransaction>>().size.toLong()
            }
            val fixture =
                fixture(
                    service,
                    properties(capacity = 2, headroom = 2, batchSize = 2),
                )
            val events = (30..34).map { event(it.toByte()) }

            // When
            events.take(4).forEach {
                fixture.queue.queue(it, DiscoverySource.GAP)
            }
            val fifth =
                async {
                    fixture.queue.queue(events.last(), DiscoverySource.GAP)
                }
            runCurrent()

            // Then
            assertTrue(fixture.queue.isAtCapacity())
            assertEquals(5.0, fixture.gauge("transactions.discovery.backlog.depth"))
            assertEquals(3.0, fixture.gauge("transactions.discovery.backlog.overshoot"))
            assertFalse(fifth.isCompleted)

            // When
            fixture.worker.flush()
            fifth.await()
            fixture.worker.flush()

            // Then
            assertEquals(0.0, fixture.gauge("transactions.discovery.backlog.depth"))
        }

    @Test
    fun `save failure retries the exact batch before newer transactions`() =
        runTest {
            // Given
            val attempts = mutableListOf<List<AttoHash>>()
            val service = mockk<UncheckedTransactionService>()
            coEvery { service.save(any()) } answers {
                val batch = firstArg<Collection<UncheckedTransaction>>()
                attempts += batch.map { it.hash }
                if (attempts.size == 1) {
                    error("simulated MySQL failure")
                }
                batch.size.toLong()
            }
            val clock = MutableClock()
            val fixture =
                fixture(
                    service,
                    properties(capacity = 3, batchSize = 2),
                    clock = clock,
                )
            val events = (4..6).map { event(it.toByte()) }
            fixture.queue.queue(events[0], DiscoverySource.DEPENDENCY)
            fixture.queue.queue(events[1], DiscoverySource.DEPENDENCY)

            // When
            fixture.worker.flush()
            fixture.queue.queue(events[2], DiscoverySource.DEPENDENCY)
            fixture.worker.flush()

            // Then
            assertEquals(1, attempts.size)
            assertFalse(fixture.queue.isAtCapacity())
            assertEquals(1.0, fixture.gauge("transactions.discovery.backlog.depth"))

            // When
            clock.advance(Duration.ofSeconds(1))
            fixture.worker.flush()

            // Then
            assertEquals(
                listOf(
                    events.take(2).map { it.transaction.hash },
                    events.take(2).map { it.transaction.hash },
                    listOf(events[2].transaction.hash),
                ),
                attempts,
            )
            assertEquals(0.0, fixture.gauge("transactions.discovery.backlog.depth"))
            assertEquals(1.0, fixture.counter("transactions.discovery.persistence.failures"))
        }

    @Test
    fun `cancelled save leaves the drained batch intact`() =
        runTest {
            // Given
            val attempts = mutableListOf<List<AttoHash>>()
            var cancelNext = true
            val service = mockk<UncheckedTransactionService>()
            coEvery { service.save(any()) } answers {
                val batch = firstArg<Collection<UncheckedTransaction>>()
                attempts += batch.map { it.hash }
                if (cancelNext) {
                    cancelNext = false
                    throw CancellationException("test cancellation")
                }
                batch.size.toLong()
            }
            val fixture = fixture(service, properties(capacity = 2, batchSize = 2))
            val events = listOf(event(7), event(8))
            events.forEach { fixture.queue.queue(it, DiscoverySource.SEND) }

            // When
            try {
                fixture.worker.flush()
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                // Expected. The worker must retain the batch.
            }
            fixture.worker.flush()

            // Then
            val expected = events.map { it.transaction.hash }
            assertEquals(listOf(expected, expected), attempts)
            assertEquals(0.0, fixture.gauge("transactions.discovery.backlog.depth"))
        }

    @Test
    fun `cancelling a suspended admission removes it without affecting buffered transactions`() =
        runTest {
            // Given
            val service = mockk<UncheckedTransactionService>(relaxed = true)
            val fixture = fixture(service, properties(capacity = 1, batchSize = 1))
            fixture.queue.queue(event(9), DiscoverySource.HEAD)
            val suspended =
                launch {
                    fixture.queue.queue(event(10), DiscoverySource.HEAD)
                }
            runCurrent()
            assertEquals(2.0, fixture.gauge("transactions.discovery.backlog.depth"))

            // When
            suspended.cancel()
            suspended.join()

            // Then
            assertEquals(1.0, fixture.gauge("transactions.discovery.backlog.depth"))
            assertTrue(fixture.queue.isAtCapacity())
        }

    @Test
    fun `duplicate transaction is published and persisted once`() =
        runTest {
            // Given
            val service = mockk<UncheckedTransactionService>()
            coEvery { service.save(any()) } answers {
                firstArg<Collection<UncheckedTransaction>>().size.toLong()
            }
            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val fixture = fixture(service, eventPublisher = eventPublisher)
            val event = event(11)

            // When
            val first = fixture.queue.queue(event, DiscoverySource.SEND)
            val pendingDuplicate = fixture.queue.queue(event, DiscoverySource.SEND)
            fixture.worker.flush()
            val committedDuplicate = fixture.queue.queue(event, DiscoverySource.SEND)

            // Then
            assertTrue(first)
            assertFalse(pendingDuplicate)
            assertFalse(committedDuplicate)
            verify(exactly = 1) { eventPublisher.publish(event) }
            assertEquals(1.0, fixture.counter("transactions.discovery.admitted", "source", "send"))
            assertEquals(1.0, fixture.counter("transactions.discovery.persisted", "source", "send"))
        }

    @Test
    fun `backlog metrics include suspended replies and record queue wait when drained`() =
        runTest {
            // Given
            val service = mockk<UncheckedTransactionService>()
            coEvery { service.save(any()) } answers {
                firstArg<Collection<UncheckedTransaction>>().size.toLong()
            }
            val clock = MutableClock()
            val fixture =
                fixture(
                    service,
                    properties(capacity = 2, batchSize = 2),
                    clock = clock,
                )
            fixture.queue.queue(event(12), DiscoverySource.GAP)
            fixture.queue.queue(event(13), DiscoverySource.GAP)
            val suspended =
                async {
                    fixture.queue.queue(event(14), DiscoverySource.GAP)
                }
            runCurrent()

            // When
            clock.advance(Duration.ofSeconds(5))

            // Then
            assertEquals(3.0, fixture.gauge("transactions.discovery.backlog.depth"))
            assertEquals(1.0, fixture.gauge("transactions.discovery.backlog.overshoot"))
            assertEquals(1.0, fixture.gauge("transactions.discovery.at.capacity"))

            // When
            fixture.worker.flush()
            suspended.await()
            fixture.worker.flush()

            // Then
            assertEquals(0.0, fixture.gauge("transactions.discovery.backlog.depth"))
            assertEquals(0.0, fixture.gauge("transactions.discovery.at.capacity"))
            val queueWait = fixture.registry.get("transactions.discovery.queue.wait").timer()
            assertEquals(3L, queueWait.count())
            assertTrue(queueWait.max(java.util.concurrent.TimeUnit.SECONDS) >= 5.0)
        }

    private fun fixture(
        service: UncheckedTransactionService,
        properties: DiscoveryProperties = properties(),
        eventPublisher: EventPublisher = mockk(relaxed = true),
        registry: SimpleMeterRegistry = SimpleMeterRegistry(),
        clock: Clock = MutableClock(),
    ): Fixture {
        properties.validate()
        val metrics = DiscoveryMetrics(registry)
        val queue =
            DiscoveryQueue(eventPublisher, properties, metrics, clock)
                .also { it.start() }
        val worker =
            DiscoveryPersistenceWorker(
                uncheckedTransactionService = service,
                queue = queue,
                properties = properties,
                metrics = metrics,
                clock = clock,
            )
        return Fixture(queue, worker, registry)
    }

    private fun properties(
        capacity: Int = 10,
        headroom: Int = 0,
        batchSize: Int = 10,
    ): DiscoveryProperties =
        DiscoveryProperties().apply {
            this.capacity = capacity
            this.headroom = headroom
            this.batchSize = batchSize
        }

    private fun event(marker: Byte): TransactionDiscovered =
        TransactionDiscovered(
            reason = null,
            transaction =
                Transaction(
                    block =
                        AttoReceiveBlock(
                            version = 0U.toAttoVersion(),
                            network = AttoNetwork.LOCAL,
                            algorithm = AttoAlgorithm.V1,
                            publicKey = AttoPublicKey(ByteArray(32) { marker }),
                            height = 2U.toAttoHeight(),
                            balance = AttoAmount.MAX,
                            timestamp = AttoInstant.now(),
                            previous = AttoHash(ByteArray(32) { (marker + 1).toByte() }),
                            sendHashAlgorithm = AttoAlgorithm.V1,
                            sendHash = AttoHash(ByteArray(32) { (marker + 2).toByte() }),
                        ),
                    signature = AttoSignature(ByteArray(64) { (marker + 3).toByte() }),
                    work = AttoWork(ByteArray(8) { (marker + 4).toByte() }),
                ),
            votes = emptyList(),
        )

    private data class Fixture(
        val queue: DiscoveryQueue,
        val worker: DiscoveryPersistenceWorker,
        val registry: SimpleMeterRegistry,
    ) {
        fun gauge(name: String): Double = registry.get(name).gauge().value()

        fun counter(
            name: String,
            vararg tags: String,
        ): Double =
            registry
                .get(name)
                .tags(*tags)
                .counter()
                .count()
    }

    private class MutableClock(
        private var current: Instant = Instant.parse("2026-08-03T00:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
