package org.atto.node.bootstrap

import org.atto.node.transaction.TransactionEvent
import org.atto.node.transaction.TransactionRejectionReasons
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionStatus

/**
 * See TransactionStatus.VOTED
 */
class TransactionVoted(
    val transaction: Transaction,
    val rejectionReason: TransactionRejectionReasons?
) : TransactionEvent(TransactionStatus.VOTED, transaction)