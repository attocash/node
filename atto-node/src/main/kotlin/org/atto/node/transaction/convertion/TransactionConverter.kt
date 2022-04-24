package org.atto.node.transaction.convertion

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.atto.node.EventPublisher
import org.atto.node.account.AccountRepository
import org.atto.node.transaction.AttoTransactionReceived
import org.atto.node.transaction.TransactionStarted
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class TransactionConverter(
    private val converters: List<TransactionConversionSupport>,
    private val accountRepository: AccountRepository,
    private val eventPublisher: EventPublisher
) {

    @EventListener
    fun toTransactionStarted(event: AttoTransactionReceived) = runBlocking {
        launch {
            val transaction = event.payload
            val account = accountRepository.getByPublicKey(transaction.block.publicKey)

            val accountChange = converters.asSequence()
                .filter { it.blockType == transaction.block.type }
                .map { it.toAccountChange(account, transaction) }
                .first()

            eventPublisher.publish(TransactionStarted(account, accountChange))
        }
    }

}