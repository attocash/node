package org.atto.node.transaction.validation.validator

import org.atto.commons.ReceiveSupportBlock
import org.atto.node.account.Account
import org.atto.node.receivable.ReceivableRepository
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.transaction.validation.TransactionValidator
import org.atto.node.transaction.validation.TransactionViolation
import org.springframework.stereotype.Component

@Component
class ReceiveValidator(
    private val receivableRepository: ReceivableRepository
) : TransactionValidator {

    override fun supports(transaction: Transaction): Boolean {
        return transaction.block is ReceiveSupportBlock
    }

    override suspend fun validate(account: Account, transaction: Transaction): TransactionViolation? {
        val block = transaction.block as ReceiveSupportBlock

        val receivable = receivableRepository.findById(block.sendHash)
            ?: return TransactionViolation(
                TransactionRejectionReason.RECEIVABLE_NOT_FOUND,
                "The account ${account.publicKey} doesn't have the receivable ${block.sendHash}"
            )

        if (account.publicKey != receivable.receiverPublicKey) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_RECEIVER,
                "The account ${account.publicKey} can't receive ${receivable.hash}. The receiver account should be ${receivable.receiverPublicKey}"
            )
        }

        if (account.balance != transaction.block.balance - receivable.amount) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_BALANCE,
                "The account ${account.publicKey} balance is ${account.balance}. The received receive transaction has the balance ${transaction.block.balance} instead of ${account.balance + receivable.amount}"
            )
        }

        return null
    }
}