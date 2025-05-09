package cash.atto.node.bootstrap.unchecked

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.AccountService
import cash.atto.node.account.getByAlgorithmAndPublicKey
import cash.atto.node.bootstrap.TransactionResolved
import cash.atto.node.bootstrap.TransactionStuck
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.transaction.validation.TransactionValidationManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

@Component
class UncheckedTransactionProcessor(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val accountRepository: AccountRepository,
    private val transactionValidationManager: TransactionValidationManager,
    private val accountService: AccountService,
    private val uncheckedTransactionService: UncheckedTransactionService,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private val mutex = Mutex()

    @Transactional
    suspend fun process() =
        mutex.withLock {
            val transactionMap =
                uncheckedTransactionRepository
                    .findReadyToValidate(1_000L)
                    .map { it.toTransaction() }
                    .toList()
                    .groupBy { Key(it.block.network, it.block.algorithm, it.publicKey) }

            transactionMap.forEach { (key, transactions) ->
                logger.debug { "Unchecked solving $key, ${transactions.map { it.hash }}" }
                var account =
                    accountRepository.getByAlgorithmAndPublicKey(
                        key.algorithm,
                        key.publicKey,
                        key.network,
                    )
                for (transaction in transactions.sortedBy { it.height }) {
                    val violation = transactionValidationManager.validate(account, transaction)
                    if (violation != null) {
                        eventPublisher.publish(TransactionStuck(violation.reason, transaction))
                        break
                    }

                    account = accountService.add(TransactionSource.BOOTSTRAP, listOf(transaction)).first()

                    logger.debug { "Resolved $transaction" }
                    eventPublisher.publish(TransactionResolved(transaction))
                }
            }

            uncheckedTransactionService.cleanUp()
        }

    private data class Key(
        val network: AttoNetwork,
        val algorithm: AttoAlgorithm,
        val publicKey: AttoPublicKey,
    )
}

@Component
class UncheckedTransactionProcessorStarter(
    val processor: UncheckedTransactionProcessor,
) {
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    suspend fun process() {
        processor.process()
    }
}
