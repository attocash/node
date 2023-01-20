package org.atto.node.bootstrap.discovery

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.atto.node.bootstrap.TransactionDiscovered
import org.atto.node.bootstrap.unchecked.UncheckedTransactionService
import org.atto.node.bootstrap.unchecked.toUncheckedTransaction
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class DiscoveryProcessor(val uncheckedTransactionService: UncheckedTransactionService) {
    private val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName(this.javaClass.simpleName))

    @EventListener
    @Async
    fun process(event: TransactionDiscovered) {
        ioScope.launch {
            uncheckedTransactionService.save(event.transaction.toUncheckedTransaction())
        }
    }
}