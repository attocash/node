package cash.atto.node.transaction.validation

import cash.atto.node.account.Account
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason

interface TransactionValidator {
    fun supports(transaction: Transaction): Boolean

    suspend fun validate(
        account: Account,
        transaction: Transaction,
    ): TransactionViolation?
}

data class TransactionViolation(
    val reason: TransactionRejectionReason,
    val message: String,
)
