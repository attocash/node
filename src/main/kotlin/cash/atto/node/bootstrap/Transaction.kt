package cash.atto.node.bootstrap

import cash.atto.node.Event
import cash.atto.node.bootstrap.unchecked.UncheckedTransaction
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.vote.Vote
import java.time.Instant

data class TransactionDiscovered(
    val reason: TransactionRejectionReason?,
    val transaction: Transaction,
    val votes: Collection<Vote>,
    override val timestamp: Instant = Instant.now(),
) : Event

data class TransactionStuck(
    val reason: TransactionRejectionReason,
    val transaction: Transaction,
    override val timestamp: Instant = Instant.now(),
) : Event

data class TransactionResolved(
    val transaction: Transaction,
    override val timestamp: Instant = Instant.now(),
) : Event

data class UncheckedTransactionSaved(
    val transaction: UncheckedTransaction,
    override val timestamp: Instant = Instant.now(),
) : Event
