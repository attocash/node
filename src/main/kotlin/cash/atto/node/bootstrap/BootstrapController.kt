package cash.atto.node.bootstrap

import cash.atto.node.bootstrap.discovery.DiscoveryPersistenceWorker
import cash.atto.node.bootstrap.discovery.DiscoveryPressureMonitor
import cash.atto.node.bootstrap.discovery.DiscoveryQueue
import cash.atto.node.bootstrap.discovery.GapDiscoverer
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionProcessor
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.sync.Mutex
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit

@Component
class BootstrapController(
    private val pressureMonitor: DiscoveryPressureMonitor,
    private val discoveryQueue: DiscoveryQueue,
    private val persistenceWorker: DiscoveryPersistenceWorker,
    private val uncheckedTransactionProcessor: UncheckedTransactionProcessor,
    private val uncheckedTransactionService: UncheckedTransactionService,
    private val gapDiscoverer: GapDiscoverer,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val runMutex = Mutex()
    private val actions =
        PriorityQueue(
            compareByDescending<BootstrapAction> { it.priority }
                .thenBy { it.lastAttemptSequence }
                .thenBy { it.order },
        )
    private var attemptSequence = 0L

    @Volatile
    private var diskCredit = 0.0

    private val deletedTransactions =
        Counter
            .builder("transactions.unchecked.cleanup.deleted")
            .description("Stale unchecked transactions deleted")
            .register(meterRegistry)

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

        val now = clock.instant().epochSecond
        actions +=
            BootstrapAction(
                decision = BootstrapDecision.MAINTENANCE,
                order = 0,
                initialWeight = RESOLUTION_INITIAL_WEIGHT,
                lastRunAtEpochSecond = now,
                operation = ::resolveUnchecked,
            )
        actions +=
            BootstrapAction(
                decision = BootstrapDecision.GAP,
                order = 1,
                initialWeight = GAP_INITIAL_WEIGHT,
                lastRunAtEpochSecond = now,
                operation = gapDiscoverer::discover,
            )
        actions +=
            BootstrapAction(
                decision = BootstrapDecision.CLEANUP,
                order = 2,
                initialWeight = CLEANUP_INITIAL_WEIGHT,
                lastRunAtEpochSecond = now,
                operation = { cleanUp(CLEANUP_LIMIT) },
            )
    }

    // Fixed-rate scheduling can overlap when an invocation suspends.
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    suspend fun run() {
        if (!runMutex.tryLock()) {
            return
        }

        try {
            accrueDiskCredit()

            if (discoveryQueue.isPhysicalBufferFull()) {
                persist(forced = true)
                return
            }

            if (diskCredit < REQUIRED_DISK_CREDIT) {
                finishWithoutAction(BootstrapDecision.PRESSURE_WAIT)
                return
            }

            if (persist(forced = false)) {
                return
            }

            executeNextAction()
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

    private suspend fun executeNextAction() {
        val action = checkNotNull(actions.poll()) { "Bootstrap action queue must not be empty" }
        try {
            attemptSequence += 1
            action.run(clock.instant().epochSecond, attemptSequence)
        } finally {
            actions += action
            consumeDiskCredit()
            record(action.decision)
        }
    }

    private suspend fun resolveUnchecked(): Int {
        val resolved = uncheckedTransactionProcessor.process()
        if (resolved > 0) {
            cleanUp(resolved.toLong())
        }
        return resolved
    }

    private suspend fun cleanUp(limit: Long): Int {
        require(limit > 0) { "Cleanup limit must be positive" }
        val deleted = uncheckedTransactionService.cleanUp(limit)
        if (deleted > 0) {
            deletedTransactions.increment(deleted.toDouble())
        }
        return deleted
    }

    private suspend fun persist(forced: Boolean): Boolean {
        val persisted =
            try {
                persistenceWorker.persist()
            } catch (e: Exception) {
                finishPersistence(forced)
                throw e
            }

        if (persisted == 0) {
            if (forced) {
                finishWithoutAction(BootstrapDecision.IDLE)
            }
            return forced
        }

        finishPersistence(forced)
        return true
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

    private inner class BootstrapAction(
        val decision: BootstrapDecision,
        val order: Int,
        private val initialWeight: Long,
        private var lastRunAtEpochSecond: Long,
        private val operation: suspend () -> Int,
    ) {
        var lastAttemptSequence = 0L
            private set

        var weight = initialWeight
            private set

        // The current epoch second is equal for every action, so this sorts exactly like
        // `weight + currentEpochSecond - lastRunAtEpochSecond`.
        val priority: Long
            get() = weight - lastRunAtEpochSecond

        suspend fun run(
            currentEpochSecond: Long,
            currentAttemptSequence: Long,
        ): Int {
            fun reset() {
                weight = initialWeight
                lastRunAtEpochSecond = currentEpochSecond
            }

            lastAttemptSequence = currentAttemptSequence
            try {
                val affected = operation()
                if (affected == 0) {
                    reset()
                } else {
                    weight = initialWeight + affected
                    lastRunAtEpochSecond = currentEpochSecond
                }
                return affected
            } catch (exception: Exception) {
                reset()
                throw exception
            }
        }
    }

    private companion object {
        const val REQUIRED_DISK_CREDIT = 1.0
        const val CLEANUP_LIMIT = 1_000L
        const val RESOLUTION_INITIAL_WEIGHT = 1_000L
        const val GAP_INITIAL_WEIGHT = 1_000L
        const val CLEANUP_INITIAL_WEIGHT = 0L
    }
}

private enum class BootstrapDecision(
    val tag: String,
) {
    MAINTENANCE("maintenance"),
    PERSISTENCE("persistence"),
    GAP("gap"),
    CLEANUP("cleanup"),
    FORCED_DRAIN("forced-drain"),
    PRESSURE_WAIT("pressure-wait"),
    IDLE("idle"),
}
