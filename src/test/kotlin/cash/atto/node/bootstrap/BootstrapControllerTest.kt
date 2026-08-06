package cash.atto.node.bootstrap

import cash.atto.node.bootstrap.discovery.DiscoveryPersistenceResult
import cash.atto.node.bootstrap.discovery.DiscoveryPersistenceWorker
import cash.atto.node.bootstrap.discovery.DiscoveryPressureMonitor
import cash.atto.node.bootstrap.discovery.DiscoveryProperties
import cash.atto.node.bootstrap.discovery.DiscoveryQueue
import cash.atto.node.bootstrap.discovery.GapDiscoverer
import cash.atto.node.bootstrap.discovery.GapDiscoveryResult
import cash.atto.node.bootstrap.unchecked.UncheckedProcessingResult
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionProcessor
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class BootstrapControllerTest {
    @Test
    fun `maintenance progress keeps priority on the following tick`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.processor.processIfDue() } returns
                UncheckedProcessingResult.Completed(selected = 10, resolved = 5)
            coEvery { fixture.processor.deleteExistingTransactions(5L) } returns 5

            // When
            fixture.controller.runOnce()
            fixture.controller.runOnce()

            // Then
            coVerify(exactly = 2) { fixture.processor.processIfDue() }
            coVerify(exactly = 2) { fixture.processor.deleteExistingTransactions(5L) }
            coVerify(exactly = 0) { fixture.worker.persistIfReady() }
            coVerify(exactly = 0) { fixture.gapDiscoverer.discoverIfDue() }
            assertEquals(2.0, fixture.decisions("maintenance"))
        }

    @Test
    fun `gap discovery runs on the tick after maintenance reports no progress`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.processor.processIfDue() } returnsMany
                listOf(
                    UncheckedProcessingResult.Completed(0, 0),
                    UncheckedProcessingResult.SkippedIdle,
                )
            coEvery { fixture.gapDiscoverer.discoverIfDue() } returns GapDiscoveryResult.Queried

            // When
            fixture.controller.runOnce()
            fixture.controller.runOnce()

            // Then
            coVerify(exactly = 2) { fixture.processor.processIfDue() }
            coVerify(exactly = 1) { fixture.worker.persistIfReady() }
            coVerify(exactly = 1) { fixture.gapDiscoverer.discoverIfDue() }
            coVerify(exactly = 0) { fixture.processor.deleteExistingTransactions(any()) }
            assertEquals(1.0, fixture.decisions("maintenance"))
            assertEquals(1.0, fixture.decisions("gap"))
        }

    @Test
    fun `fallback cleanup runs only after resolution persistence and gaps are idle`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.processor.deleteExistingTransactions(1_000L) } returns 10

            // When
            fixture.controller.runOnce()
            fixture.controller.runOnce()
            fixture.clock.advance(Duration.ofSeconds(30))
            fixture.controller.runOnce()

            // Then
            coVerify(exactly = 2) { fixture.processor.deleteExistingTransactions(1_000L) }
            assertEquals(2.0, fixture.decisions("cleanup"))
            assertEquals(1.0, fixture.decisions("idle"))
            assertEquals(1.0, fixture.diskCredit())
        }

    @Test
    fun `busy gap discovery does not allow fallback cleanup`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.gapDiscoverer.discoverIfDue() } returns GapDiscoveryResult.Busy

            // When
            fixture.controller.runOnce()

            // Then
            coVerify(exactly = 0) { fixture.processor.deleteExistingTransactions(any()) }
            assertEquals(1.0, fixture.decisions("idle"))
        }

    @Test
    fun `queued persistence runs before gap discovery when maintenance is idle`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.worker.persistIfReady() } returns DiscoveryPersistenceResult.Persisted

            // When
            fixture.controller.runOnce()

            // Then
            coVerify(exactly = 1) { fixture.processor.processIfDue() }
            coVerify(exactly = 1) { fixture.worker.persistIfReady() }
            coVerify(exactly = 0) { fixture.gapDiscoverer.discoverIfDue() }
            assertEquals(1.0, fixture.decisions("persistence"))
        }

    @Test
    fun `fractional pressure credit skips ticks without shrinking batch work`() =
        runTest {
            // Given
            val fixture = fixture()
            every { fixture.pressureMonitor.availableShare() } returns 0.5
            coEvery { fixture.processor.processIfDue() } returns
                UncheckedProcessingResult.Completed(1, 1)
            coEvery { fixture.processor.deleteExistingTransactions(1L) } returns 1

            // When
            fixture.controller.runOnce()

            // Then
            coVerify(exactly = 0) { fixture.processor.processIfDue() }
            assertEquals(0.5, fixture.diskCredit())
            assertEquals(1.0, fixture.decisions("pressure-wait"))

            // When
            fixture.controller.runOnce()

            // Then
            coVerify(exactly = 1) { fixture.processor.processIfDue() }
            assertEquals(0.0, fixture.diskCredit())
            assertEquals(1.0, fixture.decisions("maintenance"))
        }

    @Test
    fun `full pressure pauses normal work but a full physical buffer forces one drain`() =
        runTest {
            // Given
            val fixture = fixture()
            every { fixture.pressureMonitor.availableShare() } returns 0.0

            // When
            fixture.controller.runOnce()

            // Then
            coVerify(exactly = 0) { fixture.worker.persistIfReady() }
            assertEquals(1.0, fixture.decisions("pressure-wait"))

            // Given
            every { fixture.queue.isPhysicalBufferFull() } returns true
            coEvery { fixture.worker.persistIfReady() } returns DiscoveryPersistenceResult.Persisted

            // When
            fixture.controller.runOnce()

            // Then
            coVerify(exactly = 1) { fixture.worker.persistIfReady() }
            coVerify(exactly = 0) { fixture.processor.processIfDue() }
            coVerify(exactly = 0) { fixture.gapDiscoverer.discoverIfDue() }
            assertEquals(0.0, fixture.diskCredit())
            assertEquals(1.0, fixture.decisions("forced-drain"))
        }

    @Test
    fun `retry backoff blocks every bootstrap database action`() =
        runTest {
            // Given
            val fixture = fixture()
            every { fixture.worker.isRetryWaiting() } returns true

            // When
            fixture.controller.runOnce()

            // Then
            verify(exactly = 1) { fixture.worker.isRetryWaiting() }
            coVerify(exactly = 0) { fixture.worker.persistIfReady() }
            coVerify(exactly = 0) { fixture.processor.processIfDue() }
            coVerify(exactly = 0) { fixture.gapDiscoverer.discoverIfDue() }
            assertEquals(1.0, fixture.decisions("retry-wait"))
        }

    @Test
    fun `due retry runs before unchecked maintenance`() =
        runTest {
            // Given
            val fixture = fixture()
            every { fixture.worker.hasRetryBatch() } returns true
            coEvery { fixture.worker.persistIfReady() } returns DiscoveryPersistenceResult.Persisted

            // When
            fixture.controller.runOnce()

            // Then
            coVerify(exactly = 1) { fixture.worker.persistIfReady() }
            coVerify(exactly = 0) { fixture.processor.processIfDue() }
            coVerify(exactly = 0) { fixture.gapDiscoverer.discoverIfDue() }
            assertEquals(1.0, fixture.decisions("persistence"))
        }

    @Test
    fun `overlapping ticks do not start another bootstrap action`() =
        runTest {
            // Given
            val fixture = fixture()
            val maintenanceStarted = CompletableDeferred<Unit>()
            val releaseMaintenance = CompletableDeferred<Unit>()
            coEvery { fixture.processor.processIfDue() } coAnswers {
                maintenanceStarted.complete(Unit)
                releaseMaintenance.await()
                UncheckedProcessingResult.Completed(1, 1)
            }
            coEvery { fixture.processor.deleteExistingTransactions(1L) } returns 1

            // When
            val activeTick = async { fixture.controller.runOnce() }
            maintenanceStarted.await()
            fixture.controller.runOnce()

            // Then
            coVerify(exactly = 1) { fixture.processor.processIfDue() }
            coVerify(exactly = 0) { fixture.worker.persistIfReady() }
            coVerify(exactly = 0) { fixture.gapDiscoverer.discoverIfDue() }

            // When
            releaseMaintenance.complete(Unit)
            activeTick.await()

            // Then
            assertEquals(1.0, fixture.decisions("maintenance"))
        }

    @Test
    fun `unavailable pressure preserves one action per completed tick`() =
        runTest {
            // Given
            val fixture = fixture()
            every { fixture.pressureMonitor.availableShare() } returns 1.0
            coEvery { fixture.processor.processIfDue() } returns
                UncheckedProcessingResult.Completed(1, 1)
            coEvery { fixture.processor.deleteExistingTransactions(1L) } returns 1

            // When
            fixture.controller.runOnce()
            fixture.controller.runOnce()

            // Then
            coVerify(exactly = 2) { fixture.processor.processIfDue() }
            assertEquals(2.0, fixture.decisions("maintenance"))
            assertEquals(0.0, fixture.diskCredit())
        }

    private fun fixture(): Fixture {
        val registry = SimpleMeterRegistry()
        val pressureMonitor = mockk<DiscoveryPressureMonitor>()
        val queue = mockk<DiscoveryQueue>()
        val worker = mockk<DiscoveryPersistenceWorker>()
        val processor = mockk<UncheckedTransactionProcessor>()
        val gapDiscoverer = mockk<GapDiscoverer>()
        val properties = DiscoveryProperties()
        val clock = MutableClock()

        every { pressureMonitor.availableShare() } returns 1.0
        every { queue.isPhysicalBufferFull() } returns false
        every { worker.isRetryWaiting() } returns false
        every { worker.hasRetryBatch() } returns false
        coEvery { worker.persistIfReady() } returns DiscoveryPersistenceResult.Idle
        coEvery { processor.processIfDue() } returns UncheckedProcessingResult.SkippedIdle
        coEvery { processor.deleteExistingTransactions(any()) } returns 0
        coEvery { gapDiscoverer.discoverIfDue() } returns GapDiscoveryResult.Idle

        val controller =
            BootstrapController(
                pressureMonitor = pressureMonitor,
                discoveryQueue = queue,
                persistenceWorker = worker,
                uncheckedTransactionProcessor = processor,
                gapDiscoverer = gapDiscoverer,
                discoveryProperties = properties,
                clock = clock,
                meterRegistry = registry,
            )
        return Fixture(
            controller = controller,
            pressureMonitor = pressureMonitor,
            queue = queue,
            worker = worker,
            processor = processor,
            gapDiscoverer = gapDiscoverer,
            clock = clock,
            registry = registry,
        )
    }

    private data class Fixture(
        val controller: BootstrapController,
        val pressureMonitor: DiscoveryPressureMonitor,
        val queue: DiscoveryQueue,
        val worker: DiscoveryPersistenceWorker,
        val processor: UncheckedTransactionProcessor,
        val gapDiscoverer: GapDiscoverer,
        val clock: MutableClock,
        val registry: SimpleMeterRegistry,
    ) {
        fun decisions(decision: String): Double =
            registry
                .get("transactions.bootstrap.controller.decisions")
                .tag("decision", decision)
                .counter()
                .count()

        fun diskCredit(): Double =
            registry
                .get("transactions.bootstrap.controller.disk.credit")
                .gauge()
                .value()
    }

    private class MutableClock(
        private var current: Instant = Instant.parse("2026-08-06T00:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
