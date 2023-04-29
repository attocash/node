package atto.node.transaction.validation

import atto.node.account.Account
import atto.node.transaction.Transaction
import atto.node.transaction.TransactionRejectionReason

interface TransactionValidator {

    fun supports(transaction: Transaction): Boolean

    suspend fun validate(account: Account, transaction: Transaction): TransactionViolation?
}

data class TransactionViolation(val reason: TransactionRejectionReason, val message: String)