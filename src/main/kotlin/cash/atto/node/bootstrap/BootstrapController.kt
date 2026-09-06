package cash.atto.node.bootstrap

import cash.atto.node.bootstrap.discovery.DiscoveryPersistenceWorker
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
    private val loadMonitor: BootstrapLoadMonitor,
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
    private var workCredit = 0.0

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
            .builder("transactions.bootstrap.controller.work.credit", this) {
                it.workCredit
            }.description("Fractional save-latency-derived credit available for bootstrap work")
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
            accrueWorkCredit()

            if (drainPersistence()) {
                return
            }

            if (workCredit < REQUIRED_WORK_CREDIT) {
                record(BootstrapDecision.LATENCY_WAIT)
                return
            }

            executeNextAction()
        } finally {
            runMutex.unlock()
        }
    }

    private fun accrueWorkCredit() {
        loadMonitor.poll()
        workCredit += loadMonitor.availableShare()
    }

    private suspend fun executeNextAction() {
        val action = checkNotNull(actions.poll()) { "Bootstrap action queue must not be empty" }
        try {
            attemptSequence += 1
            action.run(clock.instant().epochSecond, attemptSequence)
        } finally {
            actions += action
            consumeWorkCredit()
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

    private suspend fun drainPersistence(): Boolean {
        var persisted = false
        try {
            while (persistenceWorker.persist() > 0) {
                persisted = true
            }
        } catch (e: Exception) {
            record(BootstrapDecision.PERSISTENCE)
            throw e
        }
        if (persisted) {
            record(BootstrapDecision.PERSISTENCE)
        }
        return persisted
    }

    private fun consumeWorkCredit() {
        workCredit = maxOf(0.0, workCredit - REQUIRED_WORK_CREDIT)
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
        const val REQUIRED_WORK_CREDIT = 1.0
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
    LATENCY_WAIT("latency-wait"),
}
