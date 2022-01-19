package org.atto.commons

import com.fasterxml.jackson.annotation.JsonIgnore
import java.nio.ByteBuffer
import java.time.Instant

enum class AttoBlockType(val code: UByte) {
    OPEN(0u),
    RECEIVE(1u),
    SEND(2u),
    CHANGE(3u),

    UNKNOWN(UByte.MAX_VALUE);

    companion object {
        private val map = values().associateBy(AttoBlockType::code)
        fun from(code: UByte): AttoBlockType {
            return map.getOrDefault(code, UNKNOWN)
        }
    }
}

data class AttoLink private constructor(val publicKey: AttoPublicKey?, val hash: AttoHash?) {
    companion object {
        val empty = from(AttoHash(ByteArray(32)))

        fun from(publicKey: AttoPublicKey): AttoLink {
            return AttoLink(publicKey, null)
        }

        fun from(hash: AttoHash): AttoLink {
            return AttoLink(null, hash)
        }

        fun parse(value: String): AttoLink {
            if (value.startsWith("atto_")) {
                return from(AttoAccount.parse(value).toPublicKey())
            }
            return from(AttoHash.parse(value))
        }
    }

    fun toByteArray(): ByteArray {
        return hash?.value ?: publicKey!!.value
    }

    override fun toString(): String {
        if (publicKey != null) {
            return publicKey.toAccount().toString()
        }
        return hash.toString()
    }

}

data class AttoBlock(
    var type: AttoBlockType,
    val version: UShort,
    val publicKey: AttoPublicKey,
    var height: ULong,
    val previous: AttoHash,
    val representative: AttoPublicKey,
    val link: AttoLink,
    val balance: AttoAmount,
    val amount: AttoAmount,
    val timestamp: Instant
) {

    companion object {
        val zeros32 = ByteArray(32)
        val size = 163
        val maxVersion: UShort = 0U

        fun fromByteArray(byteArray: ByteArray): AttoBlock? {
            if (size > byteArray.size) {
                return null
            }

            val type = AttoBlockType.from(byteArray.sliceArray(0 until 1)[0].toUByte())

            return AttoBlock(
                type = type,
                version = byteArray.sliceArray(1 until 3).toUShort(),
                publicKey = AttoPublicKey(byteArray.sliceArray(3 until 35)),
                height = byteArray.sliceArray(35 until 43).toULong(),
                previous = AttoHash(byteArray.sliceArray(43 until 75)),
                representative = AttoPublicKey(byteArray.sliceArray(75 until 107)),
                link = getLink(type, byteArray.sliceArray(107 until 139)),
                balance = AttoAmount(byteArray.sliceArray(139 until 147).toULong()),
                amount = AttoAmount(byteArray.sliceArray(147 until 155).toULong()),
                timestamp = byteArray.sliceArray(155 until AttoBlock.size).toInstant()
            )
        }

        private fun getLink(type: AttoBlockType, byteArray: ByteArray): AttoLink {
            if (type == AttoBlockType.SEND) {
                return AttoLink.from(AttoPublicKey(byteArray))
            }
            return AttoLink.from(AttoHash(byteArray))
        }

        fun open(publicKey: AttoPublicKey, representative: AttoPublicKey, sendBlock: AttoBlock): AttoBlock {
            if (sendBlock.type != AttoBlockType.SEND) {
                throw IllegalArgumentException("You can create Open blocks from ${AttoBlockType.SEND} but not ${sendBlock.type}")
            }
            if (sendBlock.link.publicKey != publicKey) {
                throw IllegalArgumentException("You can't create an Open block for ${sendBlock.getHash()}")
            }
            return AttoBlock(
                type = AttoBlockType.OPEN,
                version = sendBlock.version,
                publicKey = publicKey,
                height = 0U,
                previous = AttoHash(zeros32),
                representative = representative,
                link = AttoLink.from(sendBlock.getHash()),
                balance = sendBlock.amount,
                amount = sendBlock.amount,
                timestamp = Instant.now()
            )
        }
    }

    init {
        if (type == AttoBlockType.OPEN) {
            require(link.hash != null) { "Open block should have hash link" }
        } else if (type == AttoBlockType.RECEIVE) {
            require(link.hash != null) { "Receive block should have hash link" }
        } else if (type == AttoBlockType.SEND) {
            require(link.publicKey != null) { "Send block should have public key as link" }
        }
    }

    @JsonIgnore
    fun getHash(): AttoHash {
        return AttoHash(AttoHashes.hash(32, toByteArray()))
    }

    @JsonIgnore
    fun toByteArray(): ByteArray {
        return ByteBuffer.allocate(size)
            .put(type.code.toByte())
            .putShort(version.toShort())
            .put(publicKey.value)
            .putLong(height.toLong())
            .put(previous.value)
            .put(representative.value)
            .put(link.toByteArray())
            .put(balance.toByteArray())
            .put(amount.toByteArray())
            .put(timestamp.toByteArray())
            .array()
    }

    /**
     * Minimal block validation. This method doesn't check this transaction against the ledger so further validations are required.
     */
    @JsonIgnore
    fun isValid(): Boolean {
        if (type == AttoBlockType.UNKNOWN) {
            return false
        }

        if (version > maxVersion) {
            return false
        }

        if (type == AttoBlockType.OPEN && amount.raw != balance.raw) {
            return false
        }

        if (type == AttoBlockType.OPEN && height > 0UL) {
            return false
        }

        if (type != AttoBlockType.OPEN && height == 0UL) {
            return false
        }

        if (type == AttoBlockType.CHANGE && amount.raw > 0UL) {
            return false
        }

        if (type != AttoBlockType.CHANGE && amount.raw == 0UL) {
            return false
        }

        if (type == AttoBlockType.CHANGE && !link.toByteArray().contentEquals(zeros32)) {
            return false
        }

        if (type == AttoBlockType.SEND && link.publicKey!!.value.contentEquals(publicKey.value)) {
            return false
        }

        return true
    }

    fun send(linkPublicKey: AttoPublicKey, amount: AttoAmount): AttoBlock {
        return AttoBlock(
            type = AttoBlockType.SEND,
            version = version,
            publicKey = publicKey,
            height = height + 1U,
            previous = getHash(),
            representative = representative,
            link = AttoLink.from(linkPublicKey),
            balance = balance.minus(amount),
            amount = amount,
            timestamp = Instant.now()
        )
    }

    fun receive(sendBlock: AttoBlock): AttoBlock {
        if (sendBlock.type != AttoBlockType.SEND) {
            throw IllegalArgumentException("You can create Receive blocks from ${AttoBlockType.SEND} but not ${sendBlock.type}")
        }
        if (sendBlock.link.publicKey != publicKey) {
            throw IllegalArgumentException("You can't create an Receive block for ${sendBlock.getHash()}")
        }
        return AttoBlock(
            type = AttoBlockType.RECEIVE,
            version = max(version, sendBlock.version),
            publicKey = publicKey,
            height = height + 1U,
            previous = getHash(),
            representative = representative,
            link = AttoLink.from(sendBlock.getHash()),
            balance = balance.plus(sendBlock.amount),
            amount = sendBlock.amount,
            timestamp = Instant.now()
        )
    }

    fun change(representative: AttoPublicKey): AttoBlock {
        return AttoBlock(
            type = AttoBlockType.CHANGE,
            version = version,
            publicKey = publicKey,
            height = height + 1U,
            previous = getHash(),
            representative = representative,
            link = AttoLink.empty,
            balance = balance,
            amount = AttoAmount.min,
            timestamp = Instant.now()
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttoBlock

        if (type != other.type) return false
        if (version != other.version) return false
        if (publicKey != other.publicKey) return false
        if (height != other.height) return false
        if (previous != other.previous) return false
        if (representative != other.representative) return false
        if (link != other.link) return false
        if (balance != other.balance) return false
        if (amount != other.amount) return false
        if (timestamp.epochSecond != other.timestamp.epochSecond) return false // we need to ignore nanosecond

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + height.hashCode()
        result = 31 * result + previous.hashCode()
        result = 31 * result + representative.hashCode()
        result = 31 * result + link.hashCode()
        result = 31 * result + balance.hashCode()
        result = 31 * result + amount.hashCode()
        result = 31 * result + timestamp.epochSecond.hashCode() // we need to ignore nanosecond
        return result
    }
}

open class AttoTransaction(
    open val block: AttoBlock,
    open val signature: AttoSignature,
    open val work: AttoWork,
    open val hash: AttoHash = block.getHash()
) {

    open fun toByteArray(): ByteArray {
        return ByteBuffer.allocate(size)
            .put(block.toByteArray())
            .put(signature.value)
            .put(work.value)
            .array()
    }

    companion object {
        val size = 235

        fun fromByteArray(network: AttoNetwork, byteArray: ByteArray): AttoTransaction? {
            if (byteArray.size < size) {
                return null
            }

            val block = AttoBlock.fromByteArray(byteArray) ?: return null

            val receivedTimestamp = Instant.now()

            if (block.timestamp > receivedTimestamp) {
                return null
            }

            val transaction = AttoTransaction(
                block = block,
                signature = AttoSignature(byteArray.sliceArray(AttoBlock.size until AttoBlock.size + AttoSignature.size)),
                work = AttoWork(byteArray.sliceArray(AttoBlock.size + AttoSignature.size until size)),
            )

            if (!transaction.isValid(network)) {
                return null
            }

            return transaction
        }
    }

    /**
     * Minimal block validation. This method doesn't check this transaction against the ledger so further validations are required.
     */
    open fun isValid(network: AttoNetwork): Boolean {
        if (!block.isValid()) {
            return false
        }

        if (block.type == AttoBlockType.OPEN && !work.isValid(block.publicKey, network)) {
            return false
        }

        if (block.type != AttoBlockType.OPEN && !work.isValid(block.previous, network)) {
            return false
        }


        if (!signature.isValid(block.publicKey, hash.value)) {
            return false
        }

        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttoTransaction

        if (block != other.block) return false
        if (signature != other.signature) return false
        if (work != other.work) return false

        return true
    }

    override fun hashCode(): Int {
        var result = block.hashCode()
        result = 31 * result + signature.hashCode()
        result = 31 * result + work.hashCode()
        return result
    }

    override fun toString(): String {
        return "AttoTransaction(block=$block, signature=$signature, work=$work)"
    }
}

private fun max(n1: UShort, n2: UShort): UShort {
    if (n1 > n2) {
        return n1
    }
    return n2
}