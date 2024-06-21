package atto.node.transaction.validation.validator

import atto.node.account.Account
import atto.node.transaction.Transaction
import atto.node.transaction.TransactionRejectionReason
import atto.node.transaction.validation.TransactionValidator
import atto.node.transaction.validation.TransactionViolation
import cash.atto.commons.PreviousSupport
import kotlinx.datetime.toJavaInstant
import org.springframework.stereotype.Component

@Component
class BlockValidator(
    val node: atto.protocol.AttoNode,
) : TransactionValidator {
    override fun supports(transaction: Transaction): Boolean {
        return transaction.block is PreviousSupport
    }

    override suspend fun validate(
        account: Account,
        transaction: Transaction,
    ): TransactionViolation? {
        val block = transaction.block

        if (account.height < block.height - 1u) {
            return TransactionViolation(
                TransactionRejectionReason.PREVIOUS_NOT_FOUND,
                "The last known transaction is ${account.lastTransactionHash} with height ${account.height}. " +
                    "The received transaction has height ${block.height}",
            )
        }

        if (account.height >= block.height) {
            return TransactionViolation(
                TransactionRejectionReason.OLD_TRANSACTION,
                "The last known transaction is ${account.lastTransactionHash} with height ${account.height}. " +
                    "The received transaction has height ${block.height}",
            )
        }

        if (account.version > block.version) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_VERSION,
                "The last known version for the account ${account.publicKey} is ${account.version}. " +
                    "The received transaction has version ${block.version}",
            )
        }

        if (account.lastTransactionTimestamp >= block.timestamp.toJavaInstant()) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_TIMESTAMP,
                "The last known timestamp for the account ${account.publicKey} is ${account.lastTransactionTimestamp}. " +
                    "The receive transaction has ${block.timestamp}",
            )
        }

        if (!transaction.toAttoTransaction().isValid(node.network)) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_TRANSACTION,
                "The transaction ${transaction.hash} is invalid for ${node.network}. $transaction",
            )
        }

        return null
    }
}
