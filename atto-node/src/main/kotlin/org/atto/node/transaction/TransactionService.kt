package org.atto.node.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service


@Service
class TransactionService(val transactionRepository: TransactionRepository) {

    private val scope = CoroutineScope(Dispatchers.IO)

    @EventListener
    fun listen(event: TransactionConfirmed) {
        scope.launch {
            transactionRepository.save(event.transaction)
        }
    }
}