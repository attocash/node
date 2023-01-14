package org.atto.node.bootstrap.discovery

import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.vote.Vote

interface DependencyProcessor {
    fun type(): TransactionRejectionReason

    fun process(transaction: Transaction, votes: List<Vote>)
}

