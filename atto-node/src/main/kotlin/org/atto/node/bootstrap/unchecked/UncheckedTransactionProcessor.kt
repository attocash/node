package org.atto.node.bootstrap.unchecked

import kotlinx.coroutines.*
import org.atto.node.account.AccountRepository
import org.atto.node.transaction.TransactionService
import org.atto.node.transaction.validation.TransactionValidationManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class UncheckedTransactionProcessor(
    val uncheckedTransactionRepository: UncheckedTransactionRepository,
    val accountRepository: AccountRepository,
    val transactionValidationManager: TransactionValidationManager,
    val transactionService: TransactionService,
    val uncheckedTransactionService: UncheckedTransactionService
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Transactional
    suspend fun process() = withContext(singleDispatcher) {
        val transactionMap = uncheckedTransactionRepository.findReadyToValidate(5_000L).asSequence()
            .map { it.toTransaction() }
            .groupBy { it.publicKey }

        transactionMap.forEach { (publicKey, transactions) ->
            var account = accountRepository.getByPublicKey(publicKey)
            for (transaction in transactions) {
                val violation = transactionValidationManager.validate(account, transaction)
                if (violation != null) {
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
    val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName("UncheckedTransactionProcessorStarter"))

    @Scheduled(cron = "0/5 * * * * *")
    fun process() {
        ioScope.launch {
            processor.process()
        }
    }
}