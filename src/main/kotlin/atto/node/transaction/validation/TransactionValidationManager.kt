package atto.node.transaction.validation

import atto.node.EventPublisher
import atto.node.account.Account
import atto.node.account.AccountRepository
import atto.node.account.getByAlgorithmAndPublicKey
import atto.node.transaction.Transaction
import atto.node.transaction.TransactionReceived
import atto.node.transaction.TransactionRejected
import atto.node.transaction.TransactionValidated
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class TransactionValidationManager(
    private val accountRepository: AccountRepository,
    private val validators: List<TransactionValidator>,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    @EventListener
    suspend fun process(event: TransactionReceived) {
        val transaction = event.transaction
        val block = transaction.block
        val account = accountRepository.getByAlgorithmAndPublicKey(block.algorithm, block.publicKey)
        val violation = validate(account, event.transaction)
        if (violation != null) {
            logger.debug { "${violation.reason} ${violation.message}: $transaction" }
            eventPublisher.publish(TransactionRejected(violation.reason, violation.message, account, transaction))
        } else {
            eventPublisher.publish(TransactionValidated(account, transaction))
        }
    }

    suspend fun validate(
        account: Account,
        transaction: Transaction,
    ): TransactionViolation? =
        validators
            .asFlow()
            .filter { it.supports(transaction) }
            .mapNotNull { it.validate(account, transaction) }
            .firstOrNull()
}
