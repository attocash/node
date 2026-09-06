package cash.atto.node.bootstrap

import cash.atto.node.bootstrap.discovery.DiscoveryMetrics
import cash.atto.node.bootstrap.discovery.DiscoveryProperties
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionInserter
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class BootstrapLoadMonitorTest {
    @Test
    fun `starts with one batch of capacity and work share`() {
        // Given
        val fixture = fixture(capacity = 10_000, batchSize = 1_000)

        // When
        val targetCapacity = fixture.monitor.targetCapacity()

        // Then
        assertEquals(1_000, targetCapacity)
        assertEquals(0.1, fixture.monitor.availableShare())
    }

    @Test
    fun `successful full and partial batches grow by attempted rows`() {
        // Given
        val fixture = fixture()

        // When
        fixture.completeInsert(1_000, Duration.ofSeconds(1))

        // Then
        assertEquals(2_000, fixture.monitor.targetCapacity())
        assertEquals(1L, fixture.targetRatio.count())
        assertEquals(1.0, fixture.targetRatio.totalAmount())

        // When
        fixture.completeInsert(500, Duration.ofMillis(500))

        // Then
        assertEquals(2_500, fixture.monitor.targetCapacity())
        assertEquals(2L, fixture.targetRatio.count())
        assertEquals(2.0, fixture.targetRatio.totalAmount())
    }

    @Test
    fun `poll aggregates batches completed since the previous poll`() {
        // Given
        val fixture = fixture()
        fixture.completeInsertWithoutPolling(1_000, Duration.ofMillis(500))
        fixture.completeInsertWithoutPolling(500, Duration.ofMillis(250))

        // When
        fixture.monitor.poll()

        // Then
        assertEquals(2_500, fixture.monitor.targetCapacity())
        assertEquals(1L, fixture.targetRatio.count())
        assertEquals(0.5, fixture.targetRatio.totalAmount())
    }

    @Test
    fun `slow save decreases capacity by the target ratio`() {
        // Given
        val fixture = fixture()
        repeat(3) {
            fixture.completeInsert(1_000, Duration.ofMillis(500))
        }
        assertEquals(4_000, fixture.monitor.targetCapacity())

        // When
        fixture.completeInsert(1_000, Duration.ofSeconds(2))

        // Then
        assertEquals(2.0, fixture.targetRatio.max())
        assertEquals(2_000, fixture.monitor.targetCapacity())
        assertEquals(0.2, fixture.monitor.availableShare())
    }

    @Test
    fun `exposes persistence throughput target and records successful adjustment ratios`() {
        // Given
        val fixture = fixture()

        // When
        fixture.completeInsert(1_000, Duration.ofSeconds(2))

        // Then
        assertEquals(
            1_000.0,
            fixture.registry
                .get(BootstrapLoadMonitor.TARGET_PER_SECOND_METRIC_NAME)
                .gauge()
                .value(),
        )
        assertEquals(1L, fixture.targetRatio.count())
        assertEquals(2.0, fixture.targetRatio.totalAmount())
        assertEquals(2.0, fixture.targetRatio.max())
    }

    @Test
    fun `slow save never decreases below one batch`() {
        // Given
        val fixture = fixture()

        // When
        fixture.completeInsert(1_000, Duration.ofSeconds(10))

        // Then
        assertEquals(1_000, fixture.monitor.targetCapacity())
        assertEquals(0.1, fixture.monitor.availableShare())
    }

    @Test
    fun `fast saves stop growing at configured capacity`() {
        // Given
        val fixture = fixture(capacity = 2_500)

        // When
        fixture.completeInsert(1_000, Duration.ofMillis(500))
        fixture.completeInsert(1_000, Duration.ofMillis(500))
        fixture.completeInsert(500, Duration.ofMillis(250))

        // Then
        assertEquals(2_500, fixture.monitor.targetCapacity())
        assertEquals(1.0, fixture.monitor.availableShare())
    }

    @Test
    fun `save failure returns capacity to one batch`() {
        // Given
        val fixture = fixture()
        fixture.completeInsert(1_000, Duration.ofMillis(500))
        fixture.completeInsert(1_000, Duration.ofMillis(500))
        assertEquals(3_000, fixture.monitor.targetCapacity())
        fixture.failInsert(Duration.ofSeconds(2))

        // When
        fixture.monitor.poll()

        // Then
        assertEquals(1_000, fixture.monitor.targetCapacity())
        assertEquals(2L, fixture.targetRatio.count())
    }

    @Test
    fun `persistence throughput target must be positive`() {
        // Given
        val zero = properties().apply { persistenceTargetPerSecond = 0 }
        val negative = properties().apply { persistenceTargetPerSecond = -1 }

        // When
        val zeroFailure = assertThrows(IllegalArgumentException::class.java, zero::validate)
        val negativeFailure = assertThrows(IllegalArgumentException::class.java, negative::validate)

        // Then
        assertEquals("Discovery persistence target per second must be positive", zeroFailure.message)
        assertEquals("Discovery persistence target per second must be positive", negativeFailure.message)
    }

    private fun fixture(
        capacity: Int = 10_000,
        batchSize: Int = 1_000,
    ): Fixture {
        val properties = properties(capacity, batchSize).also(DiscoveryProperties::validate)
        val registry = SimpleMeterRegistry()
        val timer =
            Timer
                .builder(UncheckedTransactionInserter.METRIC_NAME)
                .register(registry)
        val persistedCounter =
            Counter
                .builder(DiscoveryMetrics.PERSISTED_METRIC_NAME)
                .tag("source", "test")
                .register(registry)
        val failureCounter =
            Counter
                .builder(DiscoveryMetrics.PERSISTENCE_FAILURE_METRIC_NAME)
                .register(registry)
        return Fixture(
            monitor = BootstrapLoadMonitor(properties, registry),
            registry = registry,
            timer = timer,
            persistedCounter = persistedCounter,
            failureCounter = failureCounter,
        )
    }

    private fun properties(
        capacity: Int = 10_000,
        batchSize: Int = 1_000,
    ): DiscoveryProperties =
        DiscoveryProperties().apply {
            this.capacity = capacity
            this.batchSize = batchSize
            persistenceTargetPerSecond = 1_000
        }

    private data class Fixture(
        val monitor: BootstrapLoadMonitor,
        val registry: SimpleMeterRegistry,
        val timer: Timer,
        val persistedCounter: Counter,
        val failureCounter: Counter,
    ) {
        val targetRatio = registry.get(BootstrapLoadMonitor.TARGET_RATIO_METRIC_NAME).summary()

        fun completeInsert(
            attemptedRows: Int,
            elapsed: Duration,
        ) {
            completeInsertWithoutPolling(attemptedRows, elapsed)
            monitor.poll()
        }

        fun completeInsertWithoutPolling(
            attemptedRows: Int,
            elapsed: Duration,
        ) {
            timer.record(elapsed)
            persistedCounter.increment(attemptedRows.toDouble())
        }

        fun failInsert(elapsed: Duration) {
            timer.record(elapsed)
            failureCounter.increment()
        }
    }
}
