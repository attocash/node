package org.atto.protocol.transaction

import com.fasterxml.jackson.annotation.JsonProperty
import org.atto.commons.*
import java.nio.ByteBuffer
import java.time.Instant

enum class TransactionStatus(val valid: Boolean) {
    RECEIVED(false),
    VALIDATED(true),
    CONFIRMED(true);

    fun isValid(): Boolean {
        return valid
    }
}

data class PublicKeyHeight(val publicKey: AttoPublicKey, val height: ULong)

data class Transaction(
    override val block: AttoBlock,
    override val signature: AttoSignature,
    override val work: AttoWork,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    override val hash: AttoHash = block.getHash(),
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    var status: TransactionStatus = TransactionStatus.RECEIVED,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val receivedTimestamp: Instant = Instant.now(),
) : AttoTransaction(block, signature, work) {

    override fun toByteArray(): ByteArray {
        return ByteBuffer.allocate(size)
            .put(block.toByteArray())
            .put(signature.value)
            .put(work.value)
            .array()
    }

    companion object {
        fun fromByteArray(network: AttoNetwork, byteArray: ByteArray): Transaction? {
            if (byteArray.size < size) {
                return null
            }

            val attoTransaction = AttoTransaction.fromByteArray(network, byteArray) ?: return null

            return Transaction(
                block = attoTransaction.block,
                signature = attoTransaction.signature,
                work = attoTransaction.work,
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

        if (!super.isValid(network)) {
            return false
        }

        return true
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
        if (signature != other.signature) return false
        if (work != other.work) return false
        if (hash != other.hash) return false
        if (status != other.status) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + block.hashCode()
        result = 31 * result + signature.hashCode()
        result = 31 * result + work.hashCode()
        result = 31 * result + hash.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }

    override fun toString(): String {
        return "Transaction(hash=$hash, status=$status, block=$block, signature=$signature, work=$work, receivedTimestamp=$receivedTimestamp)"
    }


}