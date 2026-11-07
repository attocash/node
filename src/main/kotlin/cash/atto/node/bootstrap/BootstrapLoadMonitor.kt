package cash.atto.node.bootstrap

import cash.atto.node.bootstrap.discovery.DiscoveryProperties
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class BootstrapLoadMonitor(
    private val properties: DiscoveryProperties,
    meterRegistry: MeterRegistry,
) {
    private val targetRatioSummary =
        DistributionSummary
            .builder(TARGET_RATIO_METRIC_NAME)
            .description("Unchecked persistence elapsed time divided by its proportional target")
            .register(meterRegistry)

    @Volatile
    private var targetCapacity = properties.batchSize
    private var pendingInsertCount = 0L
    private var pendingInsertNanos = 0L
    private var pendingPersistedRows = 0L
    private var pendingFailureCount = 0L

    init {
        Gauge
            .builder(TARGET_PER_SECOND_METRIC_NAME, properties) {
                it.persistenceTargetPerSecond.toDouble()
            }.description("Target unchecked transactions persisted per second")
            .register(meterRegistry)
    }

    internal fun availableShare(): Double = targetCapacity.toDouble() / properties.capacity

    internal fun targetCapacity(): Int = targetCapacity

    @Synchronized
    internal fun recordInsert(elapsedNanos: Long) {
        pendingInsertCount++
        pendingInsertNanos += elapsedNanos
    }

    @Synchronized
    internal fun recordPersisted(rowCount: Int) {
        pendingPersistedRows += rowCount
    }

    @Synchronized
    internal fun recordFailure() {
        pendingFailureCount++
    }

    @Synchronized
    internal fun poll() {
        val completedInsertDelta = pendingInsertCount
        val elapsedNanosDelta = maxOf(0L, pendingInsertNanos)
        val persistedRowsDelta = pendingPersistedRows
        val failureCountDelta = pendingFailureCount
        pendingInsertCount = 0
        pendingInsertNanos = 0
        pendingPersistedRows = 0
        pendingFailureCount = 0

        if (failureCountDelta > 0) {
            targetCapacity = properties.batchSize
            return
        }
        if (persistedRowsDelta == 0L) {
            return
        }
        check(completedInsertDelta > 0) {
            "Persisted $persistedRowsDelta unchecked transactions without an insert measurement"
        }

        val elapsed = Duration.ofNanos(elapsedNanosDelta)
        val target = targetFor(persistedRowsDelta)
        val targetRatio = elapsed.toNanos().toDouble() / target.toNanos()
        targetCapacity =
            if (elapsed <= target) {
                minOf(
                    properties.capacity.toLong(),
                    targetCapacity.toLong() + persistedRowsDelta,
                ).toInt()
            } else {
                maxOf(
                    properties.batchSize,
                    (targetCapacity * (target.toNanos().toDouble() / elapsed.toNanos())).toInt(),
                )
            }
        targetRatioSummary.record(targetRatio)
    }

    private fun targetFor(batchSize: Long): Duration {
        val proportionalTarget =
            Duration
                .ofSeconds(1)
                .multipliedBy(batchSize)
                .dividedBy(properties.persistenceTargetPerSecond)
        return if (proportionalTarget.isZero) Duration.ofNanos(1) else proportionalTarget
    }

    internal companion object {
        const val TARGET_RATIO_METRIC_NAME = "transactions.discovery.persistence.target.ratio"
        const val TARGET_PER_SECOND_METRIC_NAME = "transactions.discovery.persistence.target.per.second"
    }
}
