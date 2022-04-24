package org.atto.node.transaction

import org.atto.commons.*
import org.atto.node.Event
import org.atto.node.account.Account
import org.springframework.data.annotation.Id
import java.time.Instant

data class PublicKeyHeight(val publicKey: AttoPublicKey, val height: ULong)

data class Transaction(
    val block: AttoBlock,
    val signature: AttoSignature,
    val work: AttoWork,
    val receivedTimestamp: Instant = Instant.now(),
) {
    @Id
    val hash = block.hash

    fun toAttoTransaction(): AttoTransaction {
        return AttoTransaction(
            block = block,
            signature = signature,
            work = work
        )
    }

    fun toPublicKeyHeight(): PublicKeyHeight {
        return PublicKeyHeight(this.block.publicKey, this.block.height)
    }
}

interface AttoTransactionEvent : Event<AttoTransaction>

data class AttoTransactionReceived(override val payload: AttoTransaction) : AttoTransactionEvent

data class AttoTransactionDropped(override val payload: AttoTransaction) : AttoTransactionEvent


interface TransactionEvent : Event<Transaction> {
    /**
     * Current account status before apply the transaction
     */
    val account: Account
}

data class TransactionStarted(
    override val account: Account,
    override val payload: Transaction
) : TransactionEvent

data class TransactionValidated(
    override val account: Account,
    override val payload: Transaction
) : TransactionEvent

data class TransactionObserved(
    override val account: Account,
    override val payload: Transaction
) : TransactionEvent

data class TransactionConfirmed(
    override val account: Account,
    override val payload: Transaction
) : TransactionEvent

data class TransactionStaled(
    override val account: Account,
    override val payload: Transaction
) : TransactionEvent

enum class TransactionRejectionReason(val recoverable: Boolean) {
    INVALID_TRANSACTION(false),
    INVALID_BALANCE(false),
    INVALID_AMOUNT(false),
    INVALID_SEND(false),
    INVALID_CHANGE(false),
    INVALID_TIMESTAMP(false),
    INVALID_VERSION(false),
    INVALID_PREVIOUS(false),
    INVALID_REPRESENTATIVE(false),
    ACCOUNT_NOT_FOUND(true),
    PREVIOUS_NOT_FOUND(true),
    SEND_NOT_FOUND(true),
    SEND_NOT_CONFIRMED(true),
    SEND_ALREADY_USED(false),
    OLD_TRANSACTION(false),
}

data class TransactionRejected(
    val reason: TransactionRejectionReason,
    override val account: Account,
    override val payload: Transaction
) : TransactionEvent