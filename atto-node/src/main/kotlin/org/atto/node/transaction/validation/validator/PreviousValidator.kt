package org.atto.node.transaction.validation.validator

import org.atto.commons.PreviousSupport
import org.atto.node.account.Account
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.transaction.validation.TransactionValidator
import org.atto.node.transaction.validation.TransactionViolation
import org.springframework.stereotype.Component

@Component
class PreviousValidator : TransactionValidator {
    override fun supports(transaction: Transaction): Boolean {
        return transaction.block is PreviousSupport
    }

    override suspend fun validate(account: Account, transaction: Transaction): TransactionViolation? {
        val block = transaction.block as PreviousSupport

        if (account.lastTransactionHash != block.previous) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_PREVIOUS,
                "The account ${account.publicKey} last unknown transaction is ${account.lastTransactionHash} with height ${account.height}. The received transaction has as previous the ${block.previous} "
            )
        }
        return null
    }
}