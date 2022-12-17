package org.atto.node.transaction

import org.atto.commons.*
import org.atto.node.Event
import org.atto.node.account.Account
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.time.Instant

data class PublicKeyHeight(val publicKey: AttoPublicKey, val height: ULong)

data class Transaction(
    val block: AttoBlock,
    val signature: AttoSignature,
    val work: AttoWork,
    val receivedAt: Instant = Instant.now(),
    val persistedAt: Instant? = null,
) : Persistable<AttoHash> {
    @Id
    val hash = block.hash
    val publicKey = block.publicKey
    override fun getId(): AttoHash {
        return hash
    }

    override fun isNew(): Boolean {
        return persistedAt == null
    }

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

    override fun toString(): String {
        return "Transaction(hash=$hash, publicKey=$publicKey, block=$block, signature=$signature, work=$work, receivedAt=$receivedAt, persistedAt=$persistedAt)"
    }
}

fun AttoTransaction.toTransaction(): Transaction {
    return Transaction(
        block = block,
        signature = signature,
        work = work
    )
}

data class TransactionReceived(override val payload: Transaction) : Event<Transaction>

data class TransactionDropped(override val payload: Transaction) : Event<Transaction>
data class TransactionValidated(
    val account: Account,
    override val payload: Transaction
) : Event<Transaction>

data class TransactionObserved(
    val account: Account,
    override val payload: Transaction
) : Event<Transaction>

data class TransactionConfirmed(
    val account: Account,
    override val payload: Transaction
) : Event<Transaction>

data class TransactionStaled(
    val account: Account,
    override val payload: Transaction
) : Event<Transaction>

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
    val account: Account,
    override val payload: Transaction
) : Event<Transaction>