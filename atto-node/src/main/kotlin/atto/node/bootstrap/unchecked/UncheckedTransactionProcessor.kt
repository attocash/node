package atto.node.bootstrap.unchecked

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import atto.node.EventPublisher
import atto.node.account.AccountRepository
import atto.node.bootstrap.TransactionStuck
import atto.node.transaction.TransactionService
import atto.node.transaction.validation.TransactionValidationManager
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
    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Transactional
    suspend fun process() = withContext(singleDispatcher) {
        val transactionMap = uncheckedTransactionRepository.findReadyToValidate(10_000L)
            .map { it.toTransaction() }
            .toList()
            .groupBy { it.publicKey }

        transactionMap.forEach { (publicKey, transactions) ->
            var account = accountRepository.getByPublicKey(publicKey)
            for (transaction in transactions) {
                val violation = transactionValidationManager.validate(account, transaction)
                if (violation != null) {
                    eventPublisher.publish(TransactionStuck(violation.reason, transaction))
                    break
                }
                uncheckedTransactionService.delete(transaction.hash)
                val response = transactionService.save(transaction)
                account = response.updatedAccount
            }
        }

    }
}

@Component
class UncheckedTransactionProcessorStarter(val processor: UncheckedTransactionProcessor) {
    val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName(this.javaClass.simpleName))

    @Scheduled(cron = "0/10 * * * * *")
    fun process() {
        ioScope.launch {
            processor.process()
        }
    }
}