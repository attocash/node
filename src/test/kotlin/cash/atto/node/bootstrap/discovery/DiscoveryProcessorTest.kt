package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toAtto
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.DuplicateDetector
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.bootstrap.unchecked.UncheckedTransaction
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionService
import cash.atto.node.transaction.Transaction
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

internal class DiscoveryProcessorTest {
    @Test
    fun `skips overlapping flush and persists queued work later`() =
        runTest {
            // given
            val persistenceStarted = CompletableDeferred<Unit>()
            val resumePersistence = CompletableDeferred<Unit>()
            var saveAttempts = 0
            val savedHashes = mutableListOf<AttoHash>()
            val service = mockk<UncheckedTransactionService>()
            coEvery { service.save(any()) } coAnswers {
                saveAttempts++
                if (saveAttempts == 1) {
                    persistenceStarted.complete(Unit)
                    resumePersistence.await()
                }
                savedHashes += firstArg<Collection<UncheckedTransaction>>().map { it.hash }
            }
            val meterRegistry = SimpleMeterRegistry()
            val processor = processor(service, meterRegistry, queueMaxSize = 2, batchSize = 1, maxBatchesPerFlush = 1)
            val events = (1..2).map(::event)
            processor.flush()
            processor.process(events[0])
            val activeFlush = launch { processor.flush() }
            persistenceStarted.await()
            processor.process(events[1])

            // when
            processor.flush()

            // then
            assertEquals(1, saveAttempts)
            assertEquals(1.0, meterRegistry.queueSize())

            resumePersistence.complete(Unit)
            activeFlush.join()
            processor.flush()

            assertEquals(2, saveAttempts)
            assertEquals(events.map { it.transaction.hash }, savedHashes)
            assertEquals(0.0, meterRegistry.queueSize())
        }

    @Test
    fun `bounds admission while persistence is stalled and permits rejected work to retry`() =
        runTest {
            // given
            val persistenceStarted = CompletableDeferred<Unit>()
            val resumePersistence = CompletableDeferred<Unit>()
            val savedHashes = mutableListOf<AttoHash>()
            val service = mockk<UncheckedTransactionService>()
            coEvery { service.save(any()) } coAnswers {
                if (!persistenceStarted.isCompleted) {
                    persistenceStarted.complete(Unit)
                    resumePersistence.await()
                }
                savedHashes += firstArg<Collection<UncheckedTransaction>>().map { it.hash }
            }
            val meterRegistry = SimpleMeterRegistry()
            val processor = processor(service, meterRegistry, queueMaxSize = 2, batchSize = 2, maxBatchesPerFlush = 1)
            val events = (1..5).map(::event)
            processor.process(events[0])
            processor.process(events[1])

            // when
            val stalledFlush = launch { processor.flush() }
            persistenceStarted.await()
            processor.process(events[2])
            processor.process(events[2])
            processor.process(events[3])
            processor.process(events[4])

            // then
            assertEquals(2.0, meterRegistry.queueSize())
            assertEquals(1.0, meterRegistry.admissionRejections())

            resumePersistence.complete(Unit)
            stalledFlush.join()
            processor.flush()
            processor.process(events[4])
            processor.flush()

            assertEquals(0.0, meterRegistry.queueSize())
            assertEquals(events.map { it.transaction.hash }, savedHashes)
        }

    @Test
    fun `limits each flush to the configured batch budget`() =
        runTest {
            // given
            val savedBatchSizes = mutableListOf<Int>()
            val service = mockk<UncheckedTransactionService>()
            coEvery { service.save(any()) } answers {
                savedBatchSizes += firstArg<Collection<UncheckedTransaction>>().size
            }
            val meterRegistry = SimpleMeterRegistry()
            val processor = processor(service, meterRegistry, queueMaxSize = 5, batchSize = 2, maxBatchesPerFlush = 2)
            (1..5).map(::event).forEach { processor.process(it) }

            // when
            processor.flush()

            // then
            assertEquals(listOf(2, 2), savedBatchSizes)
            assertEquals(1.0, meterRegistry.queueSize())

            processor.flush()
            assertEquals(listOf(2, 2, 1), savedBatchSizes)
            assertEquals(0.0, meterRegistry.queueSize())
        }

    @Test
    fun `drops failed batch and permits a later flush`() =
        runTest {
            // given
            var saveAttempts = 0
            val savedHashes = mutableListOf<AttoHash>()
            val service = mockk<UncheckedTransactionService>()
            coEvery { service.save(any()) } coAnswers {
                saveAttempts++
                val batch = firstArg<Collection<UncheckedTransaction>>()
                if (saveAttempts == 1) {
                    throw IllegalStateException("Database unavailable")
                }
                savedHashes += batch.map { it.hash }
            }
            val meterRegistry = SimpleMeterRegistry()
            val processor = processor(service, meterRegistry, queueMaxSize = 3, batchSize = 2, maxBatchesPerFlush = 2)
            val events = (1..3).map(::event)
            events.forEach { processor.process(it) }

            // when
            processor.flush()
            processor.process(events[0])
            processor.process(events[1])
            processor.flush()

            // then
            assertEquals(3, saveAttempts)
            assertEquals(2.0, meterRegistry.persistenceDrops())
            assertEquals(listOf(events[2], events[0], events[1]).map { it.transaction.hash }, savedHashes)
            assertEquals(0.0, meterRegistry.queueSize())
        }

    @Test
    fun `cancellation releases flush guard and retryable reservation`() =
        runTest {
            // given
            val persistenceStarted = CompletableDeferred<Unit>()
            val holdPersistence = CompletableDeferred<Unit>()
            var saveAttempts = 0
            val savedHashes = mutableListOf<AttoHash>()
            val service = mockk<UncheckedTransactionService>()
            coEvery { service.save(any()) } coAnswers {
                saveAttempts++
                if (saveAttempts == 1) {
                    persistenceStarted.complete(Unit)
                    holdPersistence.await()
                }
                savedHashes += firstArg<Collection<UncheckedTransaction>>().map { it.hash }
            }
            val meterRegistry = SimpleMeterRegistry()
            val processor = processor(service, meterRegistry, queueMaxSize = 1, batchSize = 1, maxBatchesPerFlush = 1)
            val event = event(1)
            processor.process(event)
            val cancelledFlush = launch { processor.flush() }
            persistenceStarted.await()

            // when
            cancelledFlush.cancel()
            cancelledFlush.join()
            processor.process(event)
            processor.flush()

            // then
            assertTrue(cancelledFlush.isCancelled)
            assertEquals(2, saveAttempts)
            assertEquals(listOf(event.transaction.hash), savedHashes)
            assertEquals(1.0, meterRegistry.persistenceDrops())
            assertEquals(0.0, meterRegistry.queueSize())
        }

    @Test
    fun `flush uses one second fixed rate scheduling`() {
        // given
        val flushMethod = DiscoveryProcessor::class.java.declaredMethods.single { it.name == "flush" }

        // when
        val scheduled = checkNotNull(flushMethod.getAnnotation(Scheduled::class.java))

        // then
        assertEquals(1L, scheduled.fixedRate)
        assertEquals(-1L, scheduled.fixedDelay)
        assertEquals("", scheduled.fixedDelayString)
        assertEquals(TimeUnit.SECONDS, scheduled.timeUnit)
    }

    @Test
    fun `bounds duplicate detector entries`() {
        // given
        val detector = DuplicateDetector<Int>(10.minutes, maximumSize = 2)

        // when
        (1..10).forEach(detector::isDuplicate)

        // then
        assertTrue(detector.size <= 2)
    }

    @Test
    fun `stale cleanup cannot remove newer reservation after eviction`() {
        // given
        val detector = DuplicateDetector<Int>(10.minutes, maximumSize = 1)
        val staleReservation = detector.reserve(1)!!
        val cleanupStarted = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val cleanup =
            thread {
                cleanupStarted.countDown()
                allowCleanup.await()
                detector.remove(staleReservation)
            }
        cleanupStarted.await()

        // when
        (2..100).forEach(detector::reserve)
        detector.size
        val newerReservation = detector.reserve(1)
        allowCleanup.countDown()
        cleanup.join()

        // then
        assertNotNull(newerReservation)
        assertNull(detector.reserve(1))
    }

    @Test
    fun `rejects non-positive discovery limits`() {
        // given
        val invalidAssignments =
            listOf<DiscoveryProperties.() -> Unit>(
                { queueMaxSize = 0 },
                { batchSize = -1 },
                { maxBatchesPerFlush = 0 },
            )

        // when
        val failures =
            invalidAssignments.map { assignment ->
                assertThrows<IllegalArgumentException> {
                    DiscoveryProperties().assignment()
                }
            }

        // then
        assertEquals(3, failures.size)
    }

    private fun processor(
        service: UncheckedTransactionService,
        meterRegistry: SimpleMeterRegistry,
        queueMaxSize: Int,
        batchSize: Int,
        maxBatchesPerFlush: Int,
    ): DiscoveryProcessor =
        DiscoveryProcessor(
            service,
            DiscoveryProperties().apply {
                this.queueMaxSize = queueMaxSize
                this.batchSize = batchSize
                this.maxBatchesPerFlush = maxBatchesPerFlush
            },
            meterRegistry,
        )

    private fun event(discriminator: Int): TransactionDiscovered {
        val block =
            AttoReceiveBlock(
                network = AttoNetwork.LOCAL,
                version = 0U.toAttoVersion(),
                algorithm = AttoAlgorithm.V1,
                publicKey = AttoPublicKey(ByteArray(32) { 1 }),
                height = (discriminator + 1).toULong().toAttoHeight(),
                balance = AttoAmount(100u),
                timestamp = Instant.ofEpochSecond(discriminator.toLong()).toAtto(),
                previous = AttoHash(ByteArray(32) { discriminator.toByte() }),
                sendHashAlgorithm = AttoAlgorithm.V1,
                sendHash = AttoHash(ByteArray(32) { (discriminator + 1).toByte() }),
            )
        return TransactionDiscovered(
            reason = null,
            transaction =
                Transaction(
                    block = block,
                    signature = AttoSignature(ByteArray(64) { discriminator.toByte() }),
                    work = AttoWork(ByteArray(8) { discriminator.toByte() }),
                ),
            votes = emptyList(),
        )
    }

    private fun SimpleMeterRegistry.queueSize(): Double = get("bootstrap.discovery.queue.size").gauge().value()

    private fun SimpleMeterRegistry.admissionRejections(): Double = get("bootstrap.discovery.admission.rejections").counter().count()

    private fun SimpleMeterRegistry.persistenceDrops(): Double = get("bootstrap.discovery.persistence.drops").counter().count()
}
