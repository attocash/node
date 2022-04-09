package org.atto.node.transaction

import org.atto.node.EventPublisher
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionStatus
import org.springframework.stereotype.Service


@Service
class TransactionService(
    private val eventPublisher: EventPublisher,
    private val transactionRepository: TransactionRepository
) {

    suspend fun save(transaction: Transaction) {
        transactionRepository.save(transaction)
        if (transaction.status == TransactionStatus.CONFIRMED) {
            val event = TransactionConfirmed(transaction)
            eventPublisher.publish(event)
        }
    }

}