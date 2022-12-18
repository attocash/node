package org.atto.node.transaction.validation

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.atto.node.EventPublisher
import org.atto.node.account.Account
import org.atto.node.account.AccountRepository
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionReceived
import org.atto.node.transaction.TransactionRejected
import org.atto.node.transaction.TransactionValidated
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class TransactionValidator(
    private val accountRepository: AccountRepository,
    private val validators: List<TransactionValidationSupport>,
    private val eventPublisher: EventPublisher,
) {

    @EventListener
    fun process(event: TransactionReceived) = runBlocking {
        launch {
            val transaction = event.transaction
            val account = accountRepository.getByPublicKey(transaction.block.publicKey)
            validate(account, event.transaction)
        }
    }

    private suspend fun validate(account: Account, change: Transaction) {
        val rejectionReason = validators.asFlow()
            .filter { it.supports(change) }
            .mapNotNull { it.validate(account, change) }
            .firstOrNull()

        if (rejectionReason != null) {
            eventPublisher.publish(TransactionRejected(rejectionReason, account, change))
        } else {
            eventPublisher.publish(TransactionValidated(account, change))
        }
    }
}