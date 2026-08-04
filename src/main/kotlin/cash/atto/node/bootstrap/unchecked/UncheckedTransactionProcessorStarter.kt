package cash.atto.node.bootstrap.unchecked

import cash.atto.node.bootstrap.discovery.DiscoveryProperties
import cash.atto.node.transaction.Transaction
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.measureTime

@Component
class UncheckedTransactionProcessorStarter(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val processor: UncheckedTransactionProcessor,
    private val uncheckedTransactionService: UncheckedTransactionService,
    private val workTracker: UncheckedWorkTracker,
    private val discoveryProperties: DiscoveryProperties,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val logger = KotlinLogging.logger {}
    private val mutex = Mutex()
    private val batchSize = 1_000L
    private val maxResolutionPassNanos = TimeUnit.MILLISECONDS.toNanos(500)
    private val maxCleanupPassNanos = TimeUnit.MILLISECONDS.toNanos(500)
    private val maxCleanupBatches = 10

    private val selectionTimer =
        Timer
            .builder("transactions.unchecked.resolution.query")
            .description("Time selecting unchecked transactions eligible for resolution")
            .register(meterRegistry)
    private val processingTimer =
        Timer
            .builder("transactions.unchecked.resolution.process")
            .description("Time validating and resolving unchecked transactions")
            .register(meterRegistry)
    private val cleanupTimer =
        Timer
            .builder("transactions.unchecked.cleanup")
            .description("Time deleting bounded batches of stale unchecked transactions")
            .register(meterRegistry)
    private val resolvedTransactions =
        Counter
            .builder("transactions.unchecked.resolved")
            .description("Unchecked transactions successfully resolved")
            .register(meterRegistry)
    private val deletedTransactions =
        Counter
            .builder("transactions.unchecked.cleanup.deleted")
            .description("Stale unchecked transactions deleted")
            .register(meterRegistry)

    @Volatile
    private var idleGeneration = Long.MIN_VALUE

    @Volatile
    private var nextMaintenanceAt = Instant.EPOCH

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    suspend fun process() {
        if (mutex.isLocked || shouldSkipIdleScan()) {
            return
        }
        mutex.withLock {
            if (shouldSkipIdleScan()) {
                return
            }

            val startingGeneration = workTracker.currentGeneration()
            val passStarted = System.nanoTime()
            var madeProgress = false
            var candidateTransactions: List<Transaction>
            var resolvedCounter = 0

            do {
                candidateTransactions =
                    selectionTimer.recordSuspending {
                        uncheckedTransactionRepository
                            .findTopOldest(batchSize)
                            .map { it.toTransaction() }
                            .toList()
                    }

                val elapsed =
                    measureTime {
                        resolvedCounter = 0
                        resolvedCounter =
                            processingTimer.recordSuspending {
                                processor.process(candidateTransactions)
                            }
                    }
                if (resolvedCounter > 0) {
                    madeProgress = true
                    resolvedTransactions.increment(resolvedCounter.toDouble())
                    logger.info { "Resolved $resolvedCounter unchecked transactions in $elapsed" }
                }

                val deleted = cleanUp()
                if (deleted > 0) {
                    madeProgress = true
                    deletedTransactions.increment(deleted.toDouble())
                }
            } while (
                candidateTransactions.isNotEmpty() &&
                resolvedCounter == candidateTransactions.size &&
                System.nanoTime() - passStarted < maxResolutionPassNanos
            )

            val endingGeneration = workTracker.currentGeneration()
            if (!madeProgress && startingGeneration == endingGeneration) {
                idleGeneration = endingGeneration
                nextMaintenanceAt = clock.instant().plusSeconds(discoveryProperties.idleQueryFallbackInSeconds)
            } else {
                idleGeneration = Long.MIN_VALUE
                nextMaintenanceAt = Instant.EPOCH
            }
        }
    }

    private suspend fun cleanUp(): Int {
        val startedAt = System.nanoTime()
        var deletedTotal = 0

        repeat(maxCleanupBatches) {
            val deleted =
                cleanupTimer.recordSuspending {
                    uncheckedTransactionService.cleanUp(batchSize)
                }
            deletedTotal += deleted

            if (deleted < batchSize || System.nanoTime() - startedAt >= maxCleanupPassNanos) {
                return deletedTotal
            }
        }

        return deletedTotal
    }

    private fun shouldSkipIdleScan(): Boolean =
        workTracker.currentGeneration() == idleGeneration &&
            clock.instant().isBefore(nextMaintenanceAt)
}

private suspend fun <T> Timer.recordSuspending(block: suspend () -> T): T {
    val startedAt = System.nanoTime()
    try {
        return block()
    } finally {
        record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
    }
}
