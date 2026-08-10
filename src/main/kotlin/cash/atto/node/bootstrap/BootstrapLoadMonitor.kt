package cash.atto.node.bootstrap

import cash.atto.node.bootstrap.discovery.DiscoveryMetrics
import cash.atto.node.bootstrap.discovery.DiscoveryProperties
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionInserter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

@Component
class BootstrapLoadMonitor(
    private val properties: DiscoveryProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val targetRatioSummary =
        DistributionSummary
            .builder(TARGET_RATIO_METRIC_NAME)
            .description("Unchecked persistence elapsed time divided by its proportional target")
            .register(meterRegistry)

    private val insertTimer: Timer by lazy {
        meterRegistry.get(UncheckedTransactionInserter.METRIC_NAME).timer()
    }

    @Volatile
    private var targetCapacity = properties.batchSize
    private var observedInsertCount = 0L
    private var observedInsertNanos = 0L
    private var observedPersistedRows = 0L
    private var observedFailureCount = 0L

    init {
        Gauge
            .builder(TARGET_PER_SECOND_METRIC_NAME, properties) {
                it.persistenceTargetPerSecond.toDouble()
            }.description("Target unchecked transactions persisted per second")
            .register(meterRegistry)
    }

    internal fun availableShare(): Double = targetCapacity.toDouble() / properties.capacity

    internal fun targetCapacity(maximum: Int): Int {
        require(maximum > 0) { "Maximum discovery capacity must be positive" }
        return minOf(targetCapacity, maximum)
    }

    @Synchronized
    internal fun poll(): BootstrapLoadAdjustment? {
        val insertCount = insertTimer.count()
        val insertNanos = insertTimer.totalTime(TimeUnit.NANOSECONDS).roundToLong()
        val persistedRows =
            meterRegistry
                .find(DiscoveryMetrics.PERSISTED_METRIC_NAME)
                .counters()
                .sumOf { it.count() }
                .roundToLong()
        val failureCount =
            meterRegistry
                .get(DiscoveryMetrics.PERSISTENCE_FAILURE_METRIC_NAME)
                .counter()
                .count()
                .roundToLong()

        val completedInsertDelta = insertCount - observedInsertCount
        val elapsedNanosDelta = maxOf(0L, insertNanos - observedInsertNanos)
        val persistedRowsDelta = persistedRows - observedPersistedRows
        val failureCountDelta = failureCount - observedFailureCount

        observedInsertCount = insertCount
        observedInsertNanos = insertNanos
        observedPersistedRows = persistedRows
        observedFailureCount = failureCount

        if (failureCountDelta > 0) {
            targetCapacity = properties.batchSize
            return null
        }
        if (persistedRowsDelta == 0L) {
            return null
        }
        check(completedInsertDelta > 0) {
            "Persisted $persistedRowsDelta unchecked transactions without an insert timer measurement"
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
        return BootstrapLoadAdjustment(
            persistedRows = persistedRowsDelta,
            elapsed = elapsed,
            target = target,
            targetRatio = targetRatio,
            targetCapacity = targetCapacity,
        )
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

internal data class BootstrapLoadAdjustment(
    val persistedRows: Long,
    val elapsed: Duration,
    val target: Duration,
    val targetRatio: Double,
    val targetCapacity: Int,
)
