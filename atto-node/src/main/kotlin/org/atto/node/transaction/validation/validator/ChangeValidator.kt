package org.atto.node.transaction.validation.validator

import org.atto.commons.AttoChangeBlock
import org.atto.node.account.Account
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.transaction.validation.TransactionValidationSupport
import org.springframework.stereotype.Component

@Component
class ChangeValidator : TransactionValidationSupport {
    override fun supports(change: Transaction): Boolean {
        return change.block is AttoChangeBlock
    }

    override suspend fun validate(account: Account, change: Transaction): TransactionRejectionReason? {
        val block = change.block as AttoChangeBlock

        if (account.representative == block.representative) {
            return TransactionRejectionReason.INVALID_REPRESENTATIVE
        }

        if (account.balance != block.balance) {
            return TransactionRejectionReason.INVALID_BALANCE
        }

        return null
    }
}