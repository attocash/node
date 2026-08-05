package cash.atto.node.bootstrap.discovery

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.EnumMap

@Component
class DiscoveryMetrics(
    private val meterRegistry: MeterRegistry,
) {
    private val admittedCounters = EnumMap<DiscoverySource, Counter>(DiscoverySource::class.java)
    private val persistedCounters = EnumMap<DiscoverySource, Counter>(DiscoverySource::class.java)
    private val persistenceFailureCounter: Counter
    private val affectedRowsCounter: Counter
    private val batchTimer: Timer
    private val batchSizeSummary: DistributionSummary
    private val queueWaitTimer: Timer

    init {
        DiscoverySource.entries.forEach { source ->
            admittedCounters[source] =
                Counter
                    .builder("transactions.discovery.admitted")
                    .description("Discovered transactions admitted for unchecked persistence")
                    .tag("source", source.metricTag)
                    .register(meterRegistry)
            persistedCounters[source] =
                Counter
                    .builder("transactions.discovery.persisted")
                    .description("Discovered transactions committed to unchecked persistence")
                    .tag("source", source.metricTag)
                    .register(meterRegistry)
        }
        persistenceFailureCounter =
            Counter
                .builder("transactions.discovery.persistence.failures")
                .description("Unchecked discovery persistence failures")
                .register(meterRegistry)
        affectedRowsCounter =
            Counter
                .builder("transactions.discovery.affected")
                .description("Rows affected by unchecked discovery persistence")
                .register(meterRegistry)
        batchTimer =
            Timer
                .builder("transactions.discovery.batch")
                .description("Time spent attempting an unchecked discovery persistence batch")
                .register(meterRegistry)
        batchSizeSummary =
            DistributionSummary
                .builder("transactions.discovery.batch.size")
                .description("Discovered transactions attempted per persistence batch")
                .register(meterRegistry)
        queueWaitTimer =
            Timer
                .builder("transactions.discovery.queue.wait")
                .description("Time a discovered transaction waited before entering a persistence batch")
                .register(meterRegistry)
    }

    internal fun bind(queue: DiscoveryQueue) {
        Gauge
            .builder("transactions.discovery.backlog.depth", queue) {
                it.getBacklogDepth().toDouble()
            }.description("Discovered transactions not yet committed to unchecked persistence")
            .register(meterRegistry)
        Gauge
            .builder("transactions.discovery.in.flight", queue) {
                it.getInFlightCount().toDouble()
            }.description("Discovered transactions in the active or retrying persistence batch")
            .register(meterRegistry)
        Gauge
            .builder("transactions.discovery.capacity.target", queue) {
                it.getTargetCapacity().toDouble()
            }.description("Current disk-pressure-adjusted discovery admission target")
            .register(meterRegistry)
        Gauge
            .builder("transactions.discovery.backlog.overshoot", queue) {
                it.getBacklogOvershoot().toDouble()
            }.description("Outstanding discoveries above the target capacity")
            .register(meterRegistry)
        Gauge
            .builder("transactions.discovery.at.capacity", queue) {
                if (it.isAtCapacity()) 1.0 else 0.0
            }.description("Whether new discovery should pause")
            .register(meterRegistry)
    }

    internal fun admitted(source: DiscoverySource) {
        admittedCounters.getValue(source).increment()
    }

    internal fun persisted(batch: List<PendingDiscovery>) {
        batch
            .groupingBy { it.source }
            .eachCount()
            .forEach { (source, count) ->
                persistedCounters.getValue(source).increment(count.toDouble())
            }
    }

    internal fun persistenceFailed() {
        persistenceFailureCounter.increment()
    }

    internal fun dequeued(wait: Duration) {
        queueWaitTimer.record(if (wait.isNegative) Duration.ZERO else wait)
    }

    internal fun affectedRows(count: Long) {
        affectedRowsCounter.increment(count.toDouble())
    }

    internal fun startBatch(size: Int): Timer.Sample {
        batchSizeSummary.record(size.toDouble())
        return Timer.start(meterRegistry)
    }

    internal fun stopBatch(sample: Timer.Sample): Duration =
        Duration.ofNanos(
            sample.stop(batchTimer),
        )
}
