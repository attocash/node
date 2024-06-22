package cash.atto.node.bootstrap

import cash.atto.node.Event
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.vote.Vote

data class TransactionDiscovered(
    val reason: TransactionRejectionReason?,
    val transaction: Transaction,
    val votes: Collection<Vote>,
) : Event

data class TransactionStuck(
    val reason: TransactionRejectionReason,
    val transaction: Transaction,
) : Event

data class TransactionResolved(
    val transaction: Transaction,
) : Event
