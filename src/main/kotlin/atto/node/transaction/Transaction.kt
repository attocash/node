package atto.node.transaction

import atto.node.Event
import atto.node.account.Account
import cash.atto.commons.*
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

data class TransactionReceived(val transaction: Transaction) : Event

data class TransactionDropped(val transaction: Transaction) : Event
data class TransactionValidated(
    val account: Account,
    val transaction: Transaction
) : Event

data class TransactionSaved(
    val previousAccount: Account,
    val updatedAccount: Account,
    val transaction: Transaction
) : Event

enum class TransactionRejectionReason(val recoverable: Boolean) {
    INVALID_TRANSACTION(false),
    INVALID_BALANCE(false),
    INVALID_AMOUNT(false),
    INVALID_RECEIVER(false),
    INVALID_CHANGE(false),
    INVALID_TIMESTAMP(false),
    INVALID_VERSION(false),
    INVALID_PREVIOUS(false),
    INVALID_REPRESENTATIVE(false),
    PREVIOUS_NOT_FOUND(true),
    RECEIVABLE_NOT_FOUND(true),
    OLD_TRANSACTION(false),
}

data class TransactionRejected(
    val reason: TransactionRejectionReason,
    val message: String,
    val account: Account,
    val transaction: Transaction
) : Event