package org.atto.node.transaction.validation.validator

import org.atto.commons.AttoSendBlock
import org.atto.node.account.Account
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.transaction.validation.TransactionValidationSupport
import org.springframework.stereotype.Component

@Component
class SendValidator : TransactionValidationSupport {
    override fun supports(change: Transaction): Boolean {
        return change.block is AttoSendBlock
    }

    override suspend fun validate(account: Account, change: Transaction): TransactionRejectionReason? {
        val block = change.block as AttoSendBlock
        if (account.balance != block.balance + block.amount) {
            return TransactionRejectionReason.INVALID_AMOUNT
        }
        return null
    }
}