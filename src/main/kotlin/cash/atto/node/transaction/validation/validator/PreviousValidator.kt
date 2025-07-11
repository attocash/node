package cash.atto.node.transaction.validation.validator

import cash.atto.commons.PreviousSupport
import cash.atto.node.account.Account
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.validation.TransactionValidator
import cash.atto.node.transaction.validation.TransactionViolation
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(1)
class PreviousValidator : TransactionValidator {
    override fun supports(transaction: Transaction): Boolean = transaction.block is PreviousSupport

    override suspend fun validate(
        account: Account,
        transaction: Transaction,
    ): TransactionViolation? {
        val block = transaction.block as PreviousSupport

        if (account.lastTransactionHash != block.previous) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_PREVIOUS,
                "The account ${account.publicKey} last known transaction is " +
                    "${account.lastTransactionHash} with height ${account.height}. " +
                    "The received transaction has as previous the ${block.previous} ",
            )
        }
        return null
    }
}
