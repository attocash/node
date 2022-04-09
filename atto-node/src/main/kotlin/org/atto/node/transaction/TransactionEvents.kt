package org.atto.node.transaction

import org.atto.node.Event
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionStatus
import java.net.InetSocketAddress

abstract class TransactionEvent(
    expectedStatus: TransactionStatus,
    transaction: Transaction
) : Event<Transaction>(transaction) {
    init {
        require(expectedStatus == transaction.status)
    }
}

data class TransactionValidated(val transaction: Transaction) :
    TransactionEvent(TransactionStatus.VALIDATED, transaction)

data class TransactionObserved(val transaction: Transaction) :
    TransactionEvent(TransactionStatus.VALIDATED, transaction)

data class TransactionConfirmed(val transaction: Transaction) :
    TransactionEvent(TransactionStatus.CONFIRMED, transaction)

data class TransactionStaled(val transaction: Transaction) :
    TransactionEvent(TransactionStatus.VALIDATED, transaction)

enum class TransactionRejectionReasons(val recoverable: Boolean) {
    INVALID_TRANSACTION(false),
    INVALID_BALANCE(false),
    INVALID_AMOUNT(false),
    INVALID_LINK(false),
    INVALID_CHANGE(false),
    INVALID_TIMESTAMP(false),
    INVALID_VERSION(false),
    INVALID_PREVIOUS(false),
    ACCOUNT_NOT_FOUND(true),
    PREVIOUS_NOT_FOUND(true),
    LINK_NOT_FOUND(true),
    LINK_NOT_CONFIRMED(true), // it's possible to recover however there's no immediate action to take
    LINK_ALREADY_USED(false),
    OLD_TRANSACTION(false),
}

data class TransactionRejected(
    val socketAddress: InetSocketAddress?,
    val reasons: TransactionRejectionReasons,
    val transaction: Transaction
) : TransactionEvent(TransactionStatus.REJECTED, transaction)