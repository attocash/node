package org.atto.protocol.transaction

import com.fasterxml.jackson.annotation.JsonProperty
import org.atto.commons.*
import java.time.Instant

enum class TransactionStatus(val valid: Boolean) {
    RECEIVED(false),
    REJECTED(false),
    VALIDATED(true),

    /**
     * This status exists when a transaction has enough quorum to be approved however not all dependencies are fulfilled
     * The node will try to solve all dependencies, validate and confirm without a new election
     */
    VOTED(false),
    CONFIRMED(true);

    fun isValid(): Boolean {
        return valid
    }
}

data class PublicKeyHeight(val publicKey: AttoPublicKey, val height: ULong)

class Transaction(
    block: AttoBlock,
    signature: AttoSignature,
    work: AttoWork,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    var status: TransactionStatus = TransactionStatus.RECEIVED,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val receivedTimestamp: Instant = Instant.now(),
) : AttoTransaction(block, signature, work) {

    constructor(
        transaction: AttoTransaction,
        status: TransactionStatus = TransactionStatus.RECEIVED,
        receivedTimestamp: Instant = Instant.now(),
    ) : this(transaction.block, transaction.signature, transaction.work, status, receivedTimestamp)

    companion object {
        fun fromByteBuffer(network: AttoNetwork, byteBuffer: AttoByteBuffer): Transaction? {
            if (size > byteBuffer.size) {
                return null
            }

            val attoTransaction = AttoTransaction.fromByteBuffer(network, byteBuffer) ?: return null

            return Transaction(
                attoTransaction,
                status = TransactionStatus.RECEIVED,
                receivedTimestamp = Instant.now()
            )
        }
    }

    /**
     * Minimal block validation. This method doesn't check this transaction against the ledger so further validations are required.
     */
    override fun isValid(network: AttoNetwork): Boolean {
        if (block.timestamp > receivedTimestamp) {
            return false
        }

        return super.isValid(network)
    }

    fun toPublicKeyHeight(): PublicKeyHeight {
        return PublicKeyHeight(this.block.publicKey, this.block.height)
    }

    // receivedTimestamp is not part of the equals
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false

        other as Transaction

        if (block != other.block) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + block.hashCode()
        return result
    }

    override fun toString(): String {
        return "Transaction(${super.toString()} status=$status, receivedTimestamp=$receivedTimestamp)"
    }


}