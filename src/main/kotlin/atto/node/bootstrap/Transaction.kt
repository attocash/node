package atto.node.bootstrap

import atto.node.Event
import atto.node.transaction.Transaction
import atto.node.transaction.TransactionRejectionReason
import atto.node.vote.Vote

data class TransactionDiscovered(
    val reason: TransactionRejectionReason?,
    val transaction: Transaction,
    val votes: Collection<Vote>
) : Event


data class TransactionStuck(
    val reason: TransactionRejectionReason,
    val transaction: Transaction,
) : Event

data class TransactionResolved(
    val transaction: Transaction,
) : Event