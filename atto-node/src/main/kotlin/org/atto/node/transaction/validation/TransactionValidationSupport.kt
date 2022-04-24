package org.atto.node.transaction.validation

import org.atto.node.account.Account
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason

interface TransactionValidationSupport {

    fun supports(change: Transaction): Boolean

    suspend fun validate(account: Account, change: Transaction): TransactionRejectionReason?
}