package cash.atto.node.bootstrap

import cash.atto.node.bootstrap.discovery.DiscoveryPersistenceResult
import cash.atto.node.bootstrap.discovery.DiscoveryPersistenceWorker
import cash.atto.node.bootstrap.discovery.DiscoveryPressureMonitor
import cash.atto.node.bootstrap.discovery.DiscoveryQueue
import cash.atto.node.bootstrap.discovery.GapDiscoverer
import cash.atto.node.bootstrap.discovery.GapDiscoveryResult
import cash.atto.node.bootstrap.unchecked.UncheckedProcessingResult
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionProcessor
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.sync.Mutex
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class BootstrapController(
    private val pressureMonitor: DiscoveryPressureMonitor,
    private val discoveryQueue: DiscoveryQueue,
    private val persistenceWorker: DiscoveryPersistenceWorker,
    private val uncheckedTransactionProcessor: UncheckedTransactionProcessor,
    private val gapDiscoverer: GapDiscoverer,
    meterRegistry: MeterRegistry,
) {
    private val runMutex = Mutex()

    @Volatile
    private var diskCredit = 0.0

    private val decisionCounters =
        BootstrapDecision.entries.associateWith { decision ->
            Counter
                .builder("transactions.bootstrap.controller.decisions")
                .description("Bootstrap controller decisions")
                .tag("decision", decision.tag)
                .register(meterRegistry)
        }

    init {
        Gauge
            .builder("transactions.bootstrap.controller.disk.credit", this) {
                it.diskCredit
            }.description("Fractional PSI-derived credit available for bootstrap disk work")
            .register(meterRegistry)
    }

    // A suspending fixed-delay task blocks Spring's shared fixed-delay scheduler until it completes.
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    suspend fun runOnce() {
        if (!runMutex.tryLock()) {
            return
        }

        try {
            accrueDiskCredit()

            if (persistenceWorker.isRetryWaiting()) {
                finishWithoutAction(BootstrapDecision.RETRY_WAIT)
                return
            }

            if (discoveryQueue.isPhysicalBufferFull()) {
                persist(forced = true)
                return
            }

            if (diskCredit < REQUIRED_DISK_CREDIT) {
                finishWithoutAction(BootstrapDecision.PRESSURE_WAIT)
                return
            }

            if (persistenceWorker.hasRetryBatch()) {
                persist(forced = false)
                return
            }

            if (processUnchecked()) {
                return
            }

            if (persist(forced = false)) {
                return
            }

            discoverGaps()
        } finally {
            runMutex.unlock()
        }
    }

    private fun accrueDiskCredit() {
        val availableShare = pressureMonitor.availableShare()
        diskCredit =
            if (availableShare == 0.0) {
                0.0
            } else {
                diskCredit + availableShare
            }
    }

    private suspend fun processUnchecked(): Boolean {
        val result =
            try {
                uncheckedTransactionProcessor.processIfDue()
            } catch (exception: Exception) {
                consumeDiskCredit()
                record(BootstrapDecision.MAINTENANCE)
                throw exception
            }

        return when (result) {
            UncheckedProcessingResult.SkippedIdle -> false
            UncheckedProcessingResult.SkippedBusy -> {
                finishWithoutAction(BootstrapDecision.IDLE)
                true
            }

            is UncheckedProcessingResult.Completed -> {
                consumeDiskCredit()
                record(BootstrapDecision.MAINTENANCE)
                true
            }
        }
    }

    private suspend fun persist(forced: Boolean): Boolean {
        val result =
            try {
                persistenceWorker.persistIfReady()
            } catch (exception: Exception) {
                finishPersistence(forced)
                throw exception
            }

        when (result) {
            DiscoveryPersistenceResult.Idle -> {
                if (forced) {
                    finishWithoutAction(BootstrapDecision.IDLE)
                }
                return forced
            }

            DiscoveryPersistenceResult.Busy -> {
                finishWithoutAction(BootstrapDecision.IDLE)
            }

            DiscoveryPersistenceResult.RetryWaiting -> {
                finishWithoutAction(BootstrapDecision.RETRY_WAIT)
            }

            DiscoveryPersistenceResult.Failed,
            DiscoveryPersistenceResult.Persisted,
            -> {
                finishPersistence(forced)
            }
        }
        return true
    }

    private suspend fun discoverGaps() {
        val result =
            try {
                gapDiscoverer.discoverIfDue()
            } catch (exception: Exception) {
                consumeDiskCredit()
                record(BootstrapDecision.GAP)
                throw exception
            }

        when (result) {
            GapDiscoveryResult.Idle,
            GapDiscoveryResult.Busy,
            -> {
                finishWithoutAction(BootstrapDecision.IDLE)
            }

            GapDiscoveryResult.Queried -> {
                consumeDiskCredit()
                record(BootstrapDecision.GAP)
            }
        }
    }

    private fun finishPersistence(forced: Boolean) {
        if (forced) {
            diskCredit = 0.0
            record(BootstrapDecision.FORCED_DRAIN)
        } else {
            consumeDiskCredit()
            record(BootstrapDecision.PERSISTENCE)
        }
    }

    private fun finishWithoutAction(decision: BootstrapDecision) {
        diskCredit = minOf(REQUIRED_DISK_CREDIT, diskCredit)
        record(decision)
    }

    private fun consumeDiskCredit() {
        diskCredit = maxOf(0.0, diskCredit - REQUIRED_DISK_CREDIT)
    }

    private fun record(decision: BootstrapDecision) {
        decisionCounters.getValue(decision).increment()
    }

    private companion object {
        const val REQUIRED_DISK_CREDIT = 1.0
    }
}

private enum class BootstrapDecision(
    val tag: String,
) {
    MAINTENANCE("maintenance"),
    PERSISTENCE("persistence"),
    GAP("gap"),
    FORCED_DRAIN("forced-drain"),
    PRESSURE_WAIT("pressure-wait"),
    RETRY_WAIT("retry-wait"),
    IDLE("idle"),
}
