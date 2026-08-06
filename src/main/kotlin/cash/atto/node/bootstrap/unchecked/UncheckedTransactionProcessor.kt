package cash.atto.node.bootstrap.unchecked

import cash.atto.commons.AttoPublicKey
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.AccountService
import cash.atto.node.account.getByAlgorithmAndPublicKey
import cash.atto.node.bootstrap.TransactionResolved
import cash.atto.node.bootstrap.TransactionStuck
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.transaction.validation.TransactionValidationManager
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.transaction.support.DefaultTransactionDefinition
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

@Component
class UncheckedTransactionProcessor(
    private val accountRepository: AccountRepository,
    private val transactionValidationManager: TransactionValidationManager,
    private val accountService: AccountService,
    private val eventPublisher: EventPublisher,
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    meterRegistry: MeterRegistry,
    transactionManager: ReactiveTransactionManager,
) {
    private val batchSize = 1_000L
    private val resolutionTransaction =
        TransactionalOperator.create(
            transactionManager,
            DefaultTransactionDefinition().apply {
                isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            },
        )

    private val resolvedTransactions =
        Counter
            .builder("transactions.unchecked.resolved")
            .description("Unchecked transactions successfully resolved")
            .register(meterRegistry)

    suspend fun process(): Int {
        val candidateTransactions =
            uncheckedTransactionRepository
                .findTopOldest(batchSize)
                .map { it.toTransaction() }
                .toList()
        return resolve(candidateTransactions)
    }

    private suspend fun resolve(candidateTransactions: List<Transaction>): Int {
        if (candidateTransactions.isEmpty()) {
            return 0
        }

        var resolved = 0
        val elapsed =
            measureTime {
                resolved =
                    resolutionTransaction.executeAndAwait {
                        resolveBatch(candidateTransactions)
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
}
