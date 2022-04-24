package org.atto.node.transaction.validation.validator

import org.atto.commons.PreviousSupport
import org.atto.node.account.Account
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.transaction.validation.TransactionValidationSupport
import org.springframework.stereotype.Component

@Component
class PreviousValidator : TransactionValidationSupport {
    override fun supports(change: Transaction): Boolean {
        return change.block is PreviousSupport
    }

    override suspend fun validate(account: Account, change: Transaction): TransactionRejectionReason? {
        val block = change.block as PreviousSupport

        if (account.lastHash == block.previous) {
            return TransactionRejectionReason.INVALID_PREVIOUS
        }
        return null
    }
}