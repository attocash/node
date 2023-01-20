package org.atto.node.bootstrap

import org.atto.node.Event
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.vote.Vote

data class TransactionDiscovered(
    val reason: TransactionRejectionReason?,
    val transaction: Transaction,
    val votes: Collection<Vote>
) : Event


data class TransactionStuck(
    val reason: TransactionRejectionReason,
    val transaction: Transaction,
) : Event