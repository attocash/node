package atto.node.bootstrap.discovery

import atto.node.bootstrap.TransactionDiscovered
import atto.node.bootstrap.unchecked.UncheckedTransactionService
import atto.node.bootstrap.unchecked.toUncheckedTransaction
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class DiscoveryProcessor(val uncheckedTransactionService: UncheckedTransactionService) {
    private val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName(this.javaClass.simpleName))

    @PreDestroy
    fun preDestroy() {
        ioScope.cancel()
    }

    @EventListener
    fun process(event: TransactionDiscovered) {
        ioScope.launch {
            uncheckedTransactionService.save(event.transaction.toUncheckedTransaction())
        }
    }
}