package cash.atto.node.transaction.validation.validator

import cash.atto.commons.PreviousSupport
import cash.atto.node.account.Account
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.validation.TransactionValidator
import cash.atto.node.transaction.validation.TransactionViolation
import cash.atto.protocol.AttoNode
import kotlinx.datetime.toJavaInstant
import org.springframework.stereotype.Component

@Component
class BlockValidator(
    val node: AttoNode,
) : TransactionValidator {
    override fun supports(transaction: Transaction): Boolean {
        return transaction.block is PreviousSupport
    }

    override suspend fun validate(
        account: Account,
        transaction: Transaction,
    ): TransactionViolation? {
        val block = transaction.block

        if (account.height.toULong() < block.height.value - 1U) {
            return TransactionViolation(
                TransactionRejectionReason.PREVIOUS_NOT_FOUND,
                "The last known transaction is ${account.lastTransactionHash} with height ${account.height}. " +
                    "The received transaction has height ${block.height}",
            )
        }

        if (account.height.toULong() >= block.height.value) {
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

        if (!transaction.toAttoTransaction().isValid()) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_TRANSACTION,
                "The transaction ${transaction.hash} is invalid. $transaction",
            )
        }

        if (transaction.block.network != node.network) {
            return TransactionViolation(
                TransactionRejectionReason.INVALID_NETWORK,
                "The transaction ${transaction.hash} is invalid for ${node.network}. $transaction",
            )
        }

        return null
    }
}
