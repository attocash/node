package org.atto.node.transaction

import org.springframework.stereotype.Service


@Service
class TransactionService(val transactionRepository: TransactionRepository) {

    suspend fun save(event: TransactionConfirmed) {
        transactionRepository.save(event.transaction)
    }

}