package org.atto.node.bootstrap.unchecked

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.atto.node.bootstrap.discovery.DependencyProcessor
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.vote.Vote
import org.springframework.stereotype.Component

@Component
class PreviousNotFoundFoundDependencyProcessor(
    val uncheckedTransactionService: UncheckedTransactionService
) : DependencyProcessor {
    private val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName("PreviousNotFoundFoundDependencyProcessor"))

    override fun type() = TransactionRejectionReason.PREVIOUS_NOT_FOUND

    override fun process(transaction: Transaction, votes: List<Vote>) {
        ioScope.launch {
            uncheckedTransactionService.save(transaction.toUncheckedTransaction())
        }
    }

}