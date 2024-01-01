package atto.node.bootstrap.unchecked

import atto.node.EventPublisher
import atto.node.account.AccountRepository
import atto.node.account.getByAlgorithmAndPublicKey
import atto.node.bootstrap.TransactionResolved
import atto.node.bootstrap.TransactionStuck
import atto.node.transaction.TransactionService
import atto.node.transaction.validation.TransactionValidationManager
import cash.atto.commons.AttoAlgorithmPublicKey
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class UncheckedTransactionProcessor(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val accountRepository: AccountRepository,
    private val transactionValidationManager: TransactionValidationManager,
    private val transactionService: TransactionService,
    private val uncheckedTransactionService: UncheckedTransactionService,
    private val eventPublisher: EventPublisher
) {
    private val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    @PreDestroy
    fun stop() {
        singleDispatcher.cancel()
    }

    @Transactional
    suspend fun process() = withContext(singleDispatcher) {
        val transactionMap = uncheckedTransactionRepository.findReadyToValidate(10_000L)
            .map { it.toTransaction() }
            .toList()
            .groupBy { AttoAlgorithmPublicKey(it.block.algorithm, it.publicKey) }

        transactionMap.forEach { (algorithmPublicKey, transactions) ->
            logger.info { "Unchecked solving $algorithmPublicKey, ${transactions.map { it.hash }}" }
            var account =
                accountRepository.getByAlgorithmAndPublicKey(algorithmPublicKey.algorithm, algorithmPublicKey.publicKey)
            for (transaction in transactions) {
                val violation = transactionValidationManager.validate(account, transaction)
                if (violation != null) {
                    eventPublisher.publish(TransactionStuck(violation.reason, transaction))
                    break
                }

                uncheckedTransactionService.delete(transaction.hash)
                val response = transactionService.save(transaction)
                account = response.updatedAccount

                logger.debug { "Resolved $transaction" }
                eventPublisher.publish(TransactionResolved(transaction))
            }
        }
    }
}

@Component
class UncheckedTransactionProcessorStarter(val processor: UncheckedTransactionProcessor) {
    @Scheduled(cron = "0/10 * * * * *")
    suspend fun process() {
        processor.process()
    }
}