package cash.atto.node.bootstrap.discovery

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class DiscoveryPressureMonitorTest {
    @Test
    fun `recent spike dominates a lower avg10`() {
        // Given
        val fixture = PressureFixture(LinuxIoPressureReading(1_000, 10.0))
        fixture.refresh()

        // When: 600 ms of the next second was I/O stalled.
        fixture.reading = LinuxIoPressureReading(601_000, 10.0)
        fixture.advanceAndRefresh(Duration.ofSeconds(1))

        // Then
        assertEquals(
            DiscoveryPressureSnapshot(
                available = true,
                recent = 0.6,
                avg10 = 0.1,
                effective = 0.6,
            ),
            fixture.monitor.currentSnapshot(),
        )
        assertEquals(100, fixture.monitor.targetCapacity(250))
    }

    @Test
    fun `avg10 controls recovery when the latest interval is quieter`() {
        // Given
        val fixture = PressureFixture(LinuxIoPressureReading(1_000, 60.0))
        fixture.refresh()

        // When
        fixture.reading = LinuxIoPressureReading(101_000, 55.0)
        fixture.advanceAndRefresh(Duration.ofSeconds(1))

        // Then
        assertEquals(0.1, fixture.monitor.currentSnapshot().recent, 0.000_001)
        assertEquals(0.55, fixture.monitor.currentSnapshot().avg10, 0.000_001)
        assertEquals(0.55, fixture.monitor.currentSnapshot().effective, 0.000_001)
        assertEquals(112, fixture.monitor.targetCapacity(250))
    }

    @Test
    fun `first reading and counter reset use avg10 without inventing recent pressure`() {
        // Given
        val fixture = PressureFixture(LinuxIoPressureReading(1_000, 20.0))

        // When
        fixture.refresh()

        // Then
        assertEquals(0.0, fixture.monitor.currentSnapshot().recent)
        assertEquals(0.2, fixture.monitor.currentSnapshot().effective)

        // When
        fixture.reading = LinuxIoPressureReading(500, 30.0)
        fixture.advanceAndRefresh(Duration.ofSeconds(1))

        // Then
        assertEquals(0.0, fixture.monitor.currentSnapshot().recent)
        assertEquals(0.3, fixture.monitor.currentSnapshot().effective)
    }

    @Test
    fun `nonpositive elapsed time keeps avg10 and resets the recent baseline`() {
        // Given
        val fixture = PressureFixture(LinuxIoPressureReading(1_000, 20.0))
        fixture.refresh()

        // When
        fixture.reading = LinuxIoPressureReading(501_000, 30.0)
        fixture.refresh()

        // Then
        assertEquals(0.0, fixture.monitor.currentSnapshot().recent)
        assertEquals(0.3, fixture.monitor.currentSnapshot().effective)
    }

    @Test
    fun `unavailable source fails open immediately and establishes a fresh baseline`() {
        // Given
        val fixture = PressureFixture(LinuxIoPressureReading(1_000, 100.0))
        fixture.refresh()
        assertEquals(0, fixture.monitor.targetCapacity(250))

        // When
        fixture.reading = null
        fixture.advanceAndRefresh(Duration.ofSeconds(1))

        // Then
        assertFalse(fixture.monitor.currentSnapshot().available)
        assertEquals(250, fixture.monitor.targetCapacity(250))

        // When
        fixture.reading = LinuxIoPressureReading(901_000, 10.0)
        fixture.advanceAndRefresh(Duration.ofSeconds(1))

        // Then
        assertTrue(fixture.monitor.currentSnapshot().available)
        assertEquals(0.0, fixture.monitor.currentSnapshot().recent)
        assertEquals(0.1, fixture.monitor.currentSnapshot().effective)
    }

    @Test
    fun `capacity and metrics read the cached snapshot without resampling`() {
        // Given
        val registry = SimpleMeterRegistry()
        var reads = 0
        val monitor =
            DiscoveryPressureMonitor(
                source = {
                    reads++
                    LinuxIoPressureReading(1_000, 60.0)
                },
                meterRegistry = registry,
            )
        monitor.refresh(0)

        // When
        assertEquals(100, monitor.targetCapacity(250))
        registry.get("transactions.bootstrap.disk.pressure").gauge().value()
        registry.get("transactions.bootstrap.disk.pressure.recent").gauge().value()
        registry.get("transactions.bootstrap.disk.pressure.avg10").gauge().value()
        registry.get("transactions.bootstrap.disk.pressure.available").gauge().value()

        // Then
        assertEquals(1, reads)
    }

    @Test
    fun `parses avg10 and cumulative total from the same some line`() {
        val content =
            """
            some avg10=1.45 avg60=3.89 avg300=2.13 total=612783157
            full avg10=1.41 avg60=3.65 avg300=1.99 total=569653160
            """.trimIndent()

        assertEquals(
            LinuxIoPressureReading(
                totalStallMicros = 612_783_157,
                avg10Percent = 1.45,
            ),
            parseIoPressure(content),
        )
    }

    @Test
    fun `rejects unavailable or malformed some readings`() {
        assertNull(parseIoPressure("full avg10=1.0 total=42"))
        assertNull(parseIoPressure("some avg10=1.0 total=invalid"))
        assertNull(parseIoPressure("some avg10=invalid total=42"))
        assertNull(parseIoPressure("some avg10=NaN total=42"))
        assertNull(parseIoPressure("some avg10=1.0"))
    }

    private class PressureFixture(
        var reading: LinuxIoPressureReading?,
    ) {
        private var now = 0L
        val monitor =
            DiscoveryPressureMonitor(
                source = { reading },
                meterRegistry = SimpleMeterRegistry(),
            )

        fun refresh() {
            monitor.refresh(now)
        }

        fun advanceAndRefresh(elapsed: Duration) {
            now += elapsed.toNanos()
            refresh()
        }
    }
}
