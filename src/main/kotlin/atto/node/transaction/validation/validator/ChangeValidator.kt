package atto.node.transaction.validation.validator

import atto.node.account.Account
import atto.node.transaction.Transaction
import atto.node.transaction.TransactionRejectionReason
import atto.node.transaction.validation.TransactionValidator
import atto.node.transaction.validation.TransactionViolation
import cash.atto.commons.AttoChangeBlock
import org.springframework.stereotype.Component

@Component
class ChangeValidator : TransactionValidator {
    override fun supports(transaction: Transaction): Boolean {
        return transaction.block is AttoChangeBlock
    }

    override suspend fun validate(account: Account, transaction: Transaction): TransactionViolation? {
        val block = transaction.block as AttoChangeBlock

        if (account.representative == block.representative) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_REPRESENTATIVE,
                "The account ${account.lastTransactionHash} already has the representative ${account.representative}"
            )
        }

        if (account.balance != block.balance) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_BALANCE,
                "The account ${account.lastTransactionHash} has the balance ${account.balance}, balance can't be modified during representative change. The received transaction has balance ${block.balance}"
            )
        }

        return null
    }
}