package cash.atto.node.bootstrap.unchecked

import cash.atto.commons.AttoAlgorithmPublicKey
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.getByAlgorithmAndPublicKey
import cash.atto.node.bootstrap.TransactionResolved
import cash.atto.node.bootstrap.TransactionStuck
import cash.atto.node.transaction.TransactionSaveSource
import cash.atto.node.transaction.TransactionService
import cash.atto.node.transaction.validation.TransactionValidationManager
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

@Component
class UncheckedTransactionProcessor(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val accountRepository: AccountRepository,
    private val transactionValidationManager: TransactionValidationManager,
    private val transactionService: TransactionService,
    private val uncheckedTransactionService: UncheckedTransactionService,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    @PreDestroy
    fun stop() {
        singleDispatcher.cancel()
    }

    @Transactional
    suspend fun process() =
        withContext(singleDispatcher) {
            val transactionMap =
                uncheckedTransactionRepository
                    .findReadyToValidate(10_000L)
                    .map { it.toTransaction() }
                    .toList()
                    .groupBy { AttoAlgorithmPublicKey(it.block.algorithm, it.publicKey) }

            transactionMap.forEach { (algorithmPublicKey, transactions) ->
                logger.info { "Unchecked solving $algorithmPublicKey, ${transactions.map { it.hash }}" }
                var account =
                    transactions.first().let { transaction ->
                        accountRepository.getByAlgorithmAndPublicKey(
                            algorithmPublicKey.algorithm,
                            algorithmPublicKey.publicKey,
                            transaction.block.network,
                        )
                    }
                for (transaction in transactions) {
                    val violation = transactionValidationManager.validate(account, transaction)
                    if (violation != null) {
                        eventPublisher.publish(TransactionStuck(violation.reason, transaction))
                        break
                    }

                    uncheckedTransactionService.delete(transaction.hash)
                    val response = transactionService.save(TransactionSaveSource.BOOTSTRAP, transaction)
                    account = response.updatedAccount

                    logger.debug { "Resolved $transaction" }
                    eventPublisher.publish(TransactionResolved(transaction))
                }
            }
        }
}

@Component
class UncheckedTransactionProcessorStarter(
    val processor: UncheckedTransactionProcessor,
) {
    @Scheduled(fixedRate = 10, timeUnit = TimeUnit.SECONDS)
    suspend fun process() {
        processor.process()
    }
}
