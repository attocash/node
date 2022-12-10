package org.atto.node.transaction.validation.validator

import org.atto.commons.ReceiveSupportBlock
import org.atto.node.account.Account
import org.atto.node.receivable.AccountReceivableRepository
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.transaction.validation.TransactionValidationSupport
import org.springframework.stereotype.Component

@Component
class ReceiveValidator(
    private val accountReceivableRepository: AccountReceivableRepository
) : TransactionValidationSupport {

    override fun supports(change: Transaction): Boolean {
        return change.block is ReceiveSupportBlock
    }

    override suspend fun validate(account: Account, change: Transaction): TransactionRejectionReason? {
        val receiveBlock = change.block as ReceiveSupportBlock

        val receivable = accountReceivableRepository.findById(receiveBlock.sendHash)

        if (receivable == null) {
            return TransactionRejectionReason.SEND_NOT_FOUND
        }

        if (account.publicKey != receivable.receiverPublicKey) {
            return TransactionRejectionReason.INVALID_SEND
        }

        if (account.balance != receiveBlock.balance - receivable.amount) {
            return TransactionRejectionReason.INVALID_BALANCE
        }

        return null
    }
}