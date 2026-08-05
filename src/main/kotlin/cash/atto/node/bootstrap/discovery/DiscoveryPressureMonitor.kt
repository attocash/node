package cash.atto.node.bootstrap.discovery

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class LinuxIoPressureReading(
    val totalStallMicros: Long,
    val avg10Percent: Double,
)

fun interface LinuxIoPressureSource {
    fun read(): LinuxIoPressureReading?
}

@Component
class ProcLinuxIoPressureSource : LinuxIoPressureSource {
    override fun read(): LinuxIoPressureReading? =
        try {
            parseIoPressure(Files.readString(IO_PRESSURE_PATH))
        } catch (_: Exception) {
            null
        }

    private companion object {
        val IO_PRESSURE_PATH: Path = Path.of("/proc/pressure/io")
    }
}

@Component
class DiscoveryPressureMonitor(
    private val source: LinuxIoPressureSource,
    meterRegistry: MeterRegistry,
) {
    @Volatile
    private var snapshot = DiscoveryPressureSnapshot()

    private var previous: ObservedPressure? = null

    init {
        Gauge
            .builder("transactions.bootstrap.disk.pressure", this) {
                it.currentSnapshot().effective
            }.description("Effective Linux PSI I/O stall share controlling discovery admission")
            .register(meterRegistry)
        Gauge
            .builder("transactions.bootstrap.disk.pressure.recent", this) {
                it.currentSnapshot().recent
            }.description("I/O stall share measured from the latest cumulative PSI interval")
            .register(meterRegistry)
        Gauge
            .builder("transactions.bootstrap.disk.pressure.avg10", this) {
                it.currentSnapshot().avg10
            }.description("Linux PSI I/O some avg10 stall share")
            .register(meterRegistry)
        Gauge
            .builder("transactions.bootstrap.disk.pressure.available", this) {
                if (it.currentSnapshot().available) 1.0 else 0.0
            }.description("Whether Linux PSI I/O pressure is available to discovery admission")
            .register(meterRegistry)
    }

    @PostConstruct
    fun start() {
        refresh()
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    fun refresh() {
        refresh(System.nanoTime())
    }

    fun targetCapacity(maximum: Int): Int {
        require(maximum > 0) { "Maximum discovery capacity must be positive" }

        return (maximum * availableShare())
            .toInt()
            .coerceIn(0, maximum)
    }

    internal fun availableShare(): Double = 1.0 - snapshot.effective

    @Synchronized
    internal fun refresh(observedAtNanos: Long) {
        val reading = source.read()
        if (
            reading == null ||
            reading.totalStallMicros < 0 ||
            !reading.avg10Percent.isFinite() ||
            reading.avg10Percent < 0
        ) {
            previous = null
            snapshot = DiscoveryPressureSnapshot()
            return
        }

        val avg10 = (reading.avg10Percent / 100.0).coerceIn(0.0, 1.0)
        val recent = calculateRecentPressure(previous, reading, observedAtNanos)
        previous = ObservedPressure(observedAtNanos, reading.totalStallMicros)
        snapshot =
            DiscoveryPressureSnapshot(
                available = true,
                recent = recent,
                avg10 = avg10,
                effective = maxOf(recent, avg10),
            )
    }

    internal fun currentSnapshot(): DiscoveryPressureSnapshot = snapshot
}

internal data class DiscoveryPressureSnapshot(
    val available: Boolean = false,
    val recent: Double = 0.0,
    val avg10: Double = 0.0,
    val effective: Double = 0.0,
)

private data class ObservedPressure(
    val observedAtNanos: Long,
    val totalStallMicros: Long,
)

private fun calculateRecentPressure(
    previous: ObservedPressure?,
    current: LinuxIoPressureReading,
    observedAtNanos: Long,
): Double {
    if (
        previous == null ||
        current.totalStallMicros < previous.totalStallMicros ||
        observedAtNanos <= previous.observedAtNanos
    ) {
        return 0.0
    }

    val elapsedNanos = observedAtNanos - previous.observedAtNanos
    val stalledMicros = current.totalStallMicros - previous.totalStallMicros
    return (stalledMicros.toDouble() * NANOS_PER_MICROSECOND / elapsedNanos)
        .coerceIn(0.0, 1.0)
}

internal fun parseIoPressure(content: String): LinuxIoPressureReading? {
    val some =
        content
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("some ") }
            ?: return null
    val fields =
        some
            .splitToSequence(' ')
            .filter(String::isNotEmpty)
            .mapNotNull { field ->
                val separator = field.indexOf('=')
                if (separator < 1 || separator == field.lastIndex) {
                    null
                } else {
                    field.substring(0, separator) to field.substring(separator + 1)
                }
            }.toMap()
    val total = fields["total"]?.toLongOrNull() ?: return null
    val avg10 = fields["avg10"]?.toDoubleOrNull() ?: return null
    if (total < 0 || !avg10.isFinite() || avg10 < 0) {
        return null
    }

    return LinuxIoPressureReading(
        totalStallMicros = total,
        avg10Percent = avg10,
    )
}

private const val NANOS_PER_MICROSECOND = 1_000.0
