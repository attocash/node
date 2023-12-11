package atto.node.bootstrap.discovery

import atto.node.bootstrap.TransactionDiscovered
import atto.node.bootstrap.unchecked.UncheckedTransactionService
import atto.node.bootstrap.unchecked.toUncheckedTransaction
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class DiscoveryProcessor(val uncheckedTransactionService: UncheckedTransactionService) {

    @EventListener
    suspend fun process(event: TransactionDiscovered) {
        uncheckedTransactionService.save(event.transaction.toUncheckedTransaction())
    }
}