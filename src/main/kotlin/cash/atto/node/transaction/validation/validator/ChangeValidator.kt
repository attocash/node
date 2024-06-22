package cash.atto.node.transaction.validation.validator

import cash.atto.commons.AttoChangeBlock
import cash.atto.node.account.Account
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.validation.TransactionValidator
import cash.atto.node.transaction.validation.TransactionViolation
import org.springframework.stereotype.Component

@Component
class ChangeValidator : TransactionValidator {
    override fun supports(transaction: Transaction): Boolean {
        return transaction.block is AttoChangeBlock
    }

    override suspend fun validate(
        account: Account,
        transaction: Transaction,
    ): TransactionViolation? {
        val block = transaction.block as AttoChangeBlock

        if (account.representative == block.representative) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_REPRESENTATIVE,
                "The account ${account.lastTransactionHash} already has the representative ${account.representative}",
            )
        }

        if (account.balance != block.balance) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_BALANCE,
                "The account ${account.lastTransactionHash} has the balance ${account.balance}, " +
                    "balance can't be modified during representative change. The received transaction has balance ${block.balance}",
            )
        }

        return null
    }
}
