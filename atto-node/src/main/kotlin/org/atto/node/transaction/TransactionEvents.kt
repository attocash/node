package org.atto.node.transaction

import org.atto.node.Event
import org.atto.protocol.transaction.Transaction
import java.net.InetSocketAddress

abstract class TransactionEvent(open val transaction: Transaction) : Event<Transaction>(transaction)

data class TransactionValidated(override val transaction: Transaction) : TransactionEvent(transaction)

data class TransactionObserved(override val transaction: Transaction) : TransactionEvent(transaction)

data class TransactionConfirmed(override val transaction: Transaction) : TransactionEvent(transaction)

data class TransactionStaled(override val transaction: Transaction) : TransactionEvent(transaction)

enum class TransactionRejectionReasons {
    INVALID_TRANSACTION,
    INVALID_BALANCE,
    INVALID_AMOUNT,
    INVALID_LINK,
    INVALID_CHANGE,
    INVALID_TIMESTAMP,
    INVALID_VERSION,
    INVALID_PREVIOUS,
    ACCOUNT_NOT_FOUND,
    PREVIOUS_NOT_FOUND,
    PREVIOUS_NOT_CONFIRMED,
    LINK_NOT_FOUND,
    LINK_NOT_CONFIRMED,
    LINK_ALREADY_USED,
    OLD_TRANSACTION,
}

data class TransactionRejected(
    val socketAddress: InetSocketAddress?,
    val reasons: TransactionRejectionReasons,
    override val transaction: Transaction
) : TransactionEvent(transaction)