package org.atto.node.transaction.validation.validator

import org.atto.commons.AttoSendBlock
import org.atto.node.account.Account
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.transaction.validation.TransactionValidator
import org.atto.node.transaction.validation.TransactionViolation
import org.springframework.stereotype.Component

@Component
class SendValidator : TransactionValidator {
    override fun supports(transaction: Transaction): Boolean {
        return transaction.block is AttoSendBlock
    }

    override suspend fun validate(account: Account, transaction: Transaction): TransactionViolation? {
        val block = transaction.block as AttoSendBlock
        if (account.balance != block.balance + block.amount) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_AMOUNT,
                "The account ${account.publicKey} balance is ${account.balance}. The received send transaction has the amount ${block.amount}"
            )
        }
        return null
    }
}