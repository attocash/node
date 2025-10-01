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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

@Component
class UncheckedTransactionProcessor(
    private val accountRepository: AccountRepository,
    private val transactionValidationManager: TransactionValidationManager,
    private val accountService: AccountService,
    private val eventPublisher: EventPublisher,
) {
    private val mutex = Mutex()

    @Transactional(isolation = Isolation.READ_COMMITTED)
    suspend fun process(candidateTransactions: Collection<Transaction>): Int {
        if (candidateTransactions.isEmpty()) {
            return 0
        }

        logger.debug { "Starting resolution of ${candidateTransactions.size} unchecked transaction..." }

        mutex.withLock {
            val accountMap = HashMap<AttoPublicKey, Account>()
            val violations = HashSet<AttoPublicKey>()
            var resolvedCounter = 0

            candidateTransactions
                .forEach { transaction ->
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

                    accountMap[transaction.publicKey] = accountService.add(TransactionSource.BOOTSTRAP, listOf(transaction)).first()

                    logger.debug { "Resolved $transaction" }
                    eventPublisher.publishAfterCommit(TransactionResolved(transaction))

                    resolvedCounter++
                }

            return resolvedCounter
        }
    }
}

@Component
class UncheckedTransactionProcessorStarter(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val processor: UncheckedTransactionProcessor,
    private val uncheckedTransactionService: UncheckedTransactionService,
) {
    private val mutex = Mutex()

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    suspend fun process() {
        if (mutex.isLocked) {
            return
        }
        mutex.withLock {
            do {
                val candidateTransactions =
                    uncheckedTransactionRepository
                        .findTopOldest(1000L)
                        .map { it.toTransaction() }
                        .toList()

                var resolvedCounter = 0
                val elapsed = measureTime {
                    resolvedCounter = processor.process(candidateTransactions)
                    uncheckedTransactionService.cleanUp()
                }
                if (resolvedCounter > 0) {
                    logger.info { "Resolved $resolvedCounter unchecked transactions in $elapsed" }
                }
            } while (candidateTransactions.isNotEmpty() && resolvedCounter == candidateTransactions.size)
        }
    }
}
