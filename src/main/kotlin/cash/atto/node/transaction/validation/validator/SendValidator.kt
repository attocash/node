package cash.atto.node.transaction.validation.validator

import cash.atto.commons.AttoSendBlock
import cash.atto.node.account.Account
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.validation.TransactionValidator
import cash.atto.node.transaction.validation.TransactionViolation
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(2)
class SendValidator : TransactionValidator {
    override fun supports(transaction: Transaction): Boolean = transaction.block is AttoSendBlock

    override suspend fun validate(
        account: Account,
        transaction: Transaction,
    ): TransactionViolation? {
        val block = transaction.block as AttoSendBlock

        try {
            if (account.balance != block.balance + block.amount) {
                return TransactionViolation(
                    TransactionRejectionReason.INVALID_AMOUNT,
                    "The account ${account.publicKey} balance is ${account.balance}. " +
                        "The received send transaction has the amount ${block.amount}",
                )
            }
        } catch (e: Exception) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_AMOUNT,
                "The account ${account.publicKey} balance is ${account.balance}. " +
                    "The received send transaction has the amount ${block.amount}",
            )
        }

        return null
    }
}
