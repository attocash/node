package cash.atto.node.bootstrap.unchecked

import cash.atto.commons.AttoPublicKey
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.AccountService
import cash.atto.node.account.getByAlgorithmAndPublicKey
import cash.atto.node.bootstrap.TransactionResolved
import cash.atto.node.bootstrap.TransactionStuck
import cash.atto.node.bootstrap.discovery.DiscoveryProperties
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.transaction.validation.TransactionValidationManager
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import org.springframework.stereotype.Component
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.transaction.support.DefaultTransactionDefinition
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

@Component
class UncheckedTransactionProcessor(
    private val accountRepository: AccountRepository,
    private val transactionValidationManager: TransactionValidationManager,
    private val accountService: AccountService,
    private val eventPublisher: EventPublisher,
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val uncheckedTransactionService: UncheckedTransactionService,
    private val workTracker: UncheckedWorkTracker,
    private val discoveryProperties: DiscoveryProperties,
    meterRegistry: MeterRegistry,
    transactionManager: ReactiveTransactionManager,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val batchSize = 1_000L
    private val resolutionTransaction =
        TransactionalOperator.create(
            transactionManager,
            DefaultTransactionDefinition().apply {
                isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            },
        )

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

    suspend fun processIfDue(): UncheckedProcessingResult {
        if (shouldSkipIdleScan()) {
            return UncheckedProcessingResult.SkippedIdle
        }
        if (!mutex.tryLock()) {
            return UncheckedProcessingResult.SkippedBusy
        }

        try {
            if (shouldSkipIdleScan()) {
                return UncheckedProcessingResult.SkippedIdle
            }

            val startingGeneration = workTracker.currentGeneration()
            val candidateTransactions =
                selectionTimer.recordSuspending {
                    uncheckedTransactionRepository
                        .findTopOldest(batchSize)
                        .map { it.toTransaction() }
                        .toList()
                }
            val resolved = resolve(candidateTransactions)

            val endingGeneration = workTracker.currentGeneration()
            if (resolved == 0 && startingGeneration == endingGeneration) {
                idleGeneration = endingGeneration
                nextMaintenanceAt = clock.instant().plusSeconds(discoveryProperties.idleQueryFallbackInSeconds)
            } else {
                idleGeneration = Long.MIN_VALUE
                nextMaintenanceAt = Instant.EPOCH
            }

            return UncheckedProcessingResult.Completed(
                selected = candidateTransactions.size,
                resolved = resolved,
            )
        } finally {
            mutex.unlock()
        }
    }

    suspend fun deleteExistingTransactions(limit: Long): Int? {
        require(limit > 0) { "Cleanup limit must be positive" }
        if (!mutex.tryLock()) {
            return null
        }

        try {
            val deleted =
                cleanupTimer.recordSuspending {
                    uncheckedTransactionService.cleanUp(limit)
                }
            if (deleted > 0) {
                deletedTransactions.increment(deleted.toDouble())
            }
            return deleted
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun resolve(candidateTransactions: List<Transaction>): Int {
        if (candidateTransactions.isEmpty()) {
            return 0
        }

        var resolved = 0
        val elapsed =
            measureTime {
                resolved =
                    processingTimer.recordSuspending {
                        resolutionTransaction.executeAndAwait {
                            resolveBatch(candidateTransactions)
                        }
                    }
            }
        if (resolved > 0) {
            resolvedTransactions.increment(resolved.toDouble())
            logger.info { "Resolved $resolved unchecked transactions in $elapsed" }
        }
        return resolved
    }

    private suspend fun resolveBatch(candidateTransactions: List<Transaction>): Int {
        logger.debug { "Starting resolution of ${candidateTransactions.size} unchecked transaction..." }

        val accountMap = HashMap<AttoPublicKey, Account>()
        val violations = HashSet<AttoPublicKey>()
        var resolvedCounter = 0

        candidateTransactions.forEach { transaction ->
            if (violations.contains(transaction.publicKey)) {
                logger.debug { "Skipping $transaction because previous transaction for the same public key already failed" }
                return@forEach
            }

            logger.debug { "Unchecked solving $transaction" }
            val account =
                accountMap[transaction.publicKey] ?: accountRepository.getByAlgorithmAndPublicKey(
                    transaction.algorithm,
                    transaction.publicKey,
                    transaction.block.network,
                )

            logger.debug { "Start validation $transaction from $account" }

            val violation = transactionValidationManager.validate(account, transaction)
            if (violation != null) {
                eventPublisher.publish(TransactionStuck(violation.reason, transaction))
                violations.add(transaction.publicKey)
                return@forEach
            }

            logger.debug { "No violation found for $transaction" }

            accountMap[transaction.publicKey] =
                accountService.add(TransactionSource.BOOTSTRAP, listOf(transaction)).first()

            logger.debug { "Resolved $transaction" }
            eventPublisher.publishAfterCommit(TransactionResolved(transaction))

            resolvedCounter++
        }

        return resolvedCounter
    }

    private fun shouldSkipIdleScan(): Boolean =
        workTracker.currentGeneration() == idleGeneration &&
            clock.instant().isBefore(nextMaintenanceAt)
}

sealed interface UncheckedProcessingResult {
    data object SkippedIdle : UncheckedProcessingResult

    data object SkippedBusy : UncheckedProcessingResult

    data class Completed(
        val selected: Int,
        val resolved: Int,
    ) : UncheckedProcessingResult
}

private suspend fun <T> Timer.recordSuspending(block: suspend () -> T): T {
    val startedAt = System.nanoTime()
    try {
        return block()
    } finally {
        record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
    }
}
