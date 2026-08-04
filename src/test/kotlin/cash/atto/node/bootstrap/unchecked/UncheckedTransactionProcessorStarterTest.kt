package cash.atto.node.bootstrap.unchecked

import cash.atto.node.bootstrap.discovery.DiscoveryProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class UncheckedTransactionProcessorStarterTest {
    @Test
    fun `skips repeated scans until work changes or maintenance is due`() =
        runTest {
            // given
            val repository = mockk<UncheckedTransactionRepository>()
            val processor = mockk<UncheckedTransactionProcessor>(relaxed = true)
            val service = mockk<UncheckedTransactionService>()
            val workTracker = UncheckedWorkTracker()
            val clock = MutableClock()
            val starter = newStarter(repository, processor, service, workTracker, clock)

            coEvery { repository.findTopOldest(1_000L) } returns emptyFlow()
            coEvery { service.cleanUp(1_000L) } returns 0

            // when
            starter.process()
            starter.process()

            // then
            coVerify(exactly = 1) { repository.findTopOldest(1_000L) }
            coVerify(exactly = 1) { service.cleanUp(1_000L) }

            // when
            workTracker.markChanged()
            starter.process()

            // then
            coVerify(exactly = 2) { repository.findTopOldest(1_000L) }

            // when
            starter.process()
            clock.advance(Duration.ofSeconds(30))
            starter.process()

            // then
            coVerify(exactly = 3) { repository.findTopOldest(1_000L) }
            coVerify(exactly = 3) { service.cleanUp(1_000L) }
        }

    @Test
    fun `bounds cleanup work per scheduled pass`() =
        runTest {
            // given
            val repository = mockk<UncheckedTransactionRepository>()
            val processor = mockk<UncheckedTransactionProcessor>(relaxed = true)
            val service = mockk<UncheckedTransactionService>()
            val starter =
                newStarter(
                    repository,
                    processor,
                    service,
                    UncheckedWorkTracker(),
                    MutableClock(),
                )

            coEvery { repository.findTopOldest(1_000L) } returns emptyFlow()
            coEvery { service.cleanUp(1_000L) } returns 1_000

            // when
            starter.process()

            // then
            coVerify(exactly = 10) { service.cleanUp(1_000L) }
        }

    private fun newStarter(
        repository: UncheckedTransactionRepository,
        processor: UncheckedTransactionProcessor,
        service: UncheckedTransactionService,
        workTracker: UncheckedWorkTracker,
        clock: Clock,
    ): UncheckedTransactionProcessorStarter =
        UncheckedTransactionProcessorStarter(
            uncheckedTransactionRepository = repository,
            processor = processor,
            uncheckedTransactionService = service,
            workTracker = workTracker,
            discoveryProperties = DiscoveryProperties(),
            meterRegistry = SimpleMeterRegistry(),
            clock = clock,
        )

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
