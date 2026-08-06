package cash.atto.node.bootstrap

import cash.atto.node.bootstrap.discovery.DiscoveryPersistenceWorker
import cash.atto.node.bootstrap.discovery.DiscoveryPressureMonitor
import cash.atto.node.bootstrap.discovery.DiscoveryQueue
import cash.atto.node.bootstrap.discovery.GapDiscoverer
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionProcessor
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class BootstrapControllerTest {
    @Test
    fun `resolution progress cleans the same count and keeps resolution priority`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.processor.process() } returns 300
            coEvery { fixture.service.cleanUp(300L) } returns 300

            // When
            fixture.controller.run()
            fixture.controller.run()

            // Then
            coVerify(exactly = 2) { fixture.processor.process() }
            coVerify(exactly = 2) { fixture.service.cleanUp(300L) }
            coVerify(exactly = 2) { fixture.worker.persist() }
            coVerify(exactly = 0) { fixture.gapDiscoverer.discover() }
            assertEquals(600.0, fixture.deletedTransactions())
            assertEquals(2.0, fixture.decisions("maintenance"))
        }

    @Test
    fun `gap discovery runs on the tick after resolution reports no progress`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.gapDiscoverer.discover() } returns 1

            // When
            fixture.controller.run()
            fixture.controller.run()

            // Then
            coVerify(exactly = 1) { fixture.processor.process() }
            coVerify(exactly = 2) { fixture.worker.persist() }
            coVerify(exactly = 1) { fixture.gapDiscoverer.discover() }
            assertEquals(1.0, fixture.decisions("maintenance"))
            assertEquals(1.0, fixture.decisions("gap"))
        }

    @Test
    fun `zero result actions continue polling without fixed idle suppression`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.gapDiscoverer.discover() } returns 0

            // When
            fixture.controller.run()
            fixture.controller.run()
            fixture.controller.run()
            fixture.controller.run()

            // Then
            coVerify(exactly = 2) { fixture.processor.process() }
            coVerify(exactly = 2) { fixture.gapDiscoverer.discover() }
            coVerify(exactly = 4) { fixture.worker.persist() }
        }

    @Test
    fun `zero gap result does not suppress a later gap attempt`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.gapDiscoverer.discover() } returnsMany listOf(0, 1)

            // When
            fixture.controller.run()
            fixture.controller.run()
            fixture.controller.run()
            fixture.controller.run()

            // Then
            coVerify(exactly = 2) { fixture.gapDiscoverer.discover() }
        }

    @Test
    fun `resolution failure rethrows and consumes disk credit`() =
        runTest {
            // Given
            val fixture = fixture()
            every { fixture.pressureMonitor.availableShare() } returns 0.5
            coEvery { fixture.processor.process() } throws IllegalStateException("simulated")

            // When
            fixture.controller.run()
            expectSimulatedFailure { fixture.controller.run() }

            // Then
            coVerify(exactly = 1) { fixture.processor.process() }
            coVerify(exactly = 0) { fixture.gapDiscoverer.discover() }
            assertEquals(0.0, fixture.diskCredit())
            assertEquals(1.0, fixture.decisions("maintenance"))

            // When
            fixture.controller.run()

            // Then
            coVerify(exactly = 1) { fixture.processor.process() }
            assertEquals(0.5, fixture.diskCredit())
        }

    @Test
    fun `cleanup failure rethrows and charges the resolution action`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.processor.process() } returns 5
            coEvery { fixture.service.cleanUp(5L) } throws IllegalStateException("simulated")

            // When
            expectSimulatedFailure { fixture.controller.run() }

            // Then
            coVerify(exactly = 1) { fixture.processor.process() }
            coVerify(exactly = 1) { fixture.service.cleanUp(5L) }
            assertEquals(0.0, fixture.diskCredit())
            assertEquals(1.0, fixture.decisions("maintenance"))
        }

    @Test
    fun `gap failure rethrows and remains available after other work`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.gapDiscoverer.discover() } throws IllegalStateException("simulated") andThen 0

            // When
            fixture.controller.run()
            expectSimulatedFailure { fixture.controller.run() }
            fixture.controller.run()
            fixture.controller.run()

            // Then
            coVerify(exactly = 2) { fixture.gapDiscoverer.discover() }
        }

    @Test
    fun `queued persistence runs before gap discovery when resolution is idle`() =
        runTest {
            // Given
            val fixture = fixture()
            coEvery { fixture.worker.persist() } returnsMany
                listOf(0, 1)

            // When
            fixture.controller.run()
            fixture.controller.run()

            // Then
            coVerify(exactly = 1) { fixture.processor.process() }
            coVerify(exactly = 2) { fixture.worker.persist() }
            coVerify(exactly = 0) { fixture.gapDiscoverer.discover() }
            assertEquals(1.0, fixture.decisions("persistence"))
        }

    @Test
    fun `fractional pressure credit skips ticks without shrinking work`() =
        runTest {
            // Given
            val fixture = fixture()
            every { fixture.pressureMonitor.availableShare() } returns 0.5
            coEvery { fixture.processor.process() } returns 1
            coEvery { fixture.service.cleanUp(1L) } returns 1

            // When
            fixture.controller.run()

            // Then
            coVerify(exactly = 0) { fixture.processor.process() }
            assertEquals(0.5, fixture.diskCredit())

            // When
            fixture.controller.run()

            // Then
            coVerify(exactly = 1) { fixture.processor.process() }
            coVerify(exactly = 1) { fixture.service.cleanUp(1L) }
            assertEquals(0.0, fixture.diskCredit())
        }

    @Test
    fun `full physical buffer forces persistence despite disk pressure`() =
        runTest {
            // Given
            val fixture = fixture()
            every { fixture.pressureMonitor.availableShare() } returns 0.0
            every { fixture.queue.isPhysicalBufferFull() } returns true
            coEvery { fixture.worker.persist() } returns 1

            // When
            fixture.controller.run()

            // Then
            coVerify(exactly = 1) { fixture.worker.persist() }
            coVerify(exactly = 0) { fixture.processor.process() }
            assertEquals(1.0, fixture.decisions("forced-drain"))
        }

    @Test
    fun `overlapping ticks do not start another bootstrap action`() =
        runTest {
            // Given
            val fixture = fixture()
            val resolutionStarted = CompletableDeferred<Unit>()
            val releaseResolution = CompletableDeferred<Unit>()
            coEvery { fixture.processor.process() } coAnswers {
                resolutionStarted.complete(Unit)
                releaseResolution.await()
                1
            }
            coEvery { fixture.service.cleanUp(1L) } returns 1

            // When
            val activeTick = async { fixture.controller.run() }
            resolutionStarted.await()
            fixture.controller.run()

            // Then
            coVerify(exactly = 1) { fixture.processor.process() }

            // When
            releaseResolution.complete(Unit)
            activeTick.await()

            // Then
            coVerify(exactly = 1) { fixture.service.cleanUp(1L) }
        }

    private fun fixture(): Fixture {
        val registry = SimpleMeterRegistry()
        val pressureMonitor = mockk<DiscoveryPressureMonitor>()
        val queue = mockk<DiscoveryQueue>()
        val worker = mockk<DiscoveryPersistenceWorker>()
        val processor = mockk<UncheckedTransactionProcessor>()
        val service = mockk<UncheckedTransactionService>()
        val gapDiscoverer = mockk<GapDiscoverer>()
        val clock = MutableClock()

        every { pressureMonitor.availableShare() } returns 1.0
        every { queue.isPhysicalBufferFull() } returns false
        coEvery { worker.persist() } returns 0
        coEvery { processor.process() } returns 0
        coEvery { service.cleanUp(any()) } returns 0
        coEvery { gapDiscoverer.discover() } returns 0

        val controller =
            BootstrapController(
                pressureMonitor = pressureMonitor,
                discoveryQueue = queue,
                persistenceWorker = worker,
                uncheckedTransactionProcessor = processor,
                uncheckedTransactionService = service,
                gapDiscoverer = gapDiscoverer,
                clock = clock,
                meterRegistry = registry,
            )
        return Fixture(
            controller = controller,
            pressureMonitor = pressureMonitor,
            queue = queue,
            worker = worker,
            processor = processor,
            service = service,
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
        val service: UncheckedTransactionService,
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

        fun deletedTransactions(): Double =
            registry
                .get("transactions.unchecked.cleanup.deleted")
                .counter()
                .count()
    }

    private class MutableClock(
        private var current: Instant = Instant.parse("2026-08-06T00:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current
    }

    private suspend fun expectSimulatedFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected simulated failure")
        } catch (exception: IllegalStateException) {
            assertEquals("simulated", exception.message)
        }
    }
}
