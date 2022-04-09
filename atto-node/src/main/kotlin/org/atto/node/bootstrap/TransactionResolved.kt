package org.atto.node.bootstrap

import org.atto.node.transaction.TransactionEvent
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionStatus

/**
 * Sent when a transaction with VOTED status is confirmed
 */
class TransactionResolved(
    val transaction: Transaction,
) : TransactionEvent(TransactionStatus.CONFIRMED, transaction)