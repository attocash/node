package cash.atto.node.transaction.validation

import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.getByAlgorithmAndPublicKey
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionReceived
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.transaction.TransactionValidated
import cash.atto.node.transaction.TransactionValidationFailed
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
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
        val account: Account
        val violation: TransactionViolation?
        try {
            account = accountRepository.getByAlgorithmAndPublicKey(block.algorithm, block.publicKey, block.network)
            violation = validate(account, transaction)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error(exception) { "Failed to validate transaction ${transaction.hash}" }
            eventPublisher.publish(TransactionValidationFailed(transaction, exception))
            return
        }
        if (violation != null) {
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
            .onEach { logger.debug { "${it.reason} ${it.message}: $transaction" } }
            .firstOrNull()
}
