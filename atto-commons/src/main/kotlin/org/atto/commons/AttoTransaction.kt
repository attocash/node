package org.atto.commons

import java.time.Instant

val maxVersion: UShort = 0U

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

abstract class AttoBlock(
    val version: UShort,
    val publicKey: AttoPublicKey,
    val height: ULong,
    val balance: AttoAmount,
    val timestamp: Instant,
) {
    companion object {
        fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoBlock? {
            val type = byteBuffer.getBlockType()
            return when (type) {
                AttoBlockType.SEND -> {
                    AttoSendBlock.fromByteBuffer(byteBuffer)
                }
                AttoBlockType.RECEIVE -> {
                    AttoReceiveBlock.fromByteBuffer(byteBuffer)
                }
                AttoBlockType.OPEN -> {
                    AttoOpenBlock.fromByteBuffer(byteBuffer)
                }
                AttoBlockType.CHANGE -> {
                    AttoChangeBlock.fromByteBuffer(byteBuffer)
                }
                AttoBlockType.UNKNOWN -> {
                    return null
                }
            }
        }
    }

    abstract fun toByteBuffer(): AttoByteBuffer

    abstract fun getSize(): Int

    abstract fun getHash(): AttoHash

    abstract fun getType(): AttoBlockType

    abstract fun getWorkInfo(): ByteArray // ugly name rename

    open fun isValid(): Boolean {
        return version <= maxVersion
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttoBlock) return false

        if (version != other.version) return false
        if (publicKey != other.publicKey) return false
        if (height != other.height) return false
        if (balance != other.balance) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + height.hashCode()
        result = 31 * result + balance.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

sealed interface PreviousSupport {
    fun getPrevious(): AttoHash
}

sealed interface ReceiveSupport {
    fun getSendHash(): AttoHash
}

sealed interface RepresentativeSupport {
    fun getRepresentative(): AttoPublicKey
}

class AttoSendBlock(
    version: UShort,
    publicKey: AttoPublicKey,
    height: ULong,
    balance: AttoAmount,
    timestamp: Instant,
    private val previous: AttoHash,
    val receiverPublicKey: AttoPublicKey,
    val amount: AttoAmount,
) : AttoBlock(version = version, publicKey = publicKey, height = height, balance = balance, timestamp = timestamp),
    PreviousSupport {
    private val hash = toByteBuffer().getHash()

    companion object {
        val size = 131

        internal fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoSendBlock? {
            if (size > byteBuffer.size) {
                return null
            }

            val blockType = byteBuffer.getBlockType(0)
            if (blockType != AttoBlockType.SEND) {
                throw IllegalArgumentException("Invalid block type: $blockType")
            }

            return AttoSendBlock(
                version = byteBuffer.getUShort(),
                publicKey = byteBuffer.getPublicKey(),
                height = byteBuffer.getULong(),
                balance = byteBuffer.getAmount(),
                timestamp = byteBuffer.getInstant(),
                previous = byteBuffer.getHash(),
                receiverPublicKey = byteBuffer.getPublicKey(),
                amount = byteBuffer.getAmount(),
            )
        }
    }

    override fun toByteBuffer(): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(size)
        return byteBuffer
            .add(getType())
            .add(version)
            .add(publicKey)
            .add(height)
            .add(balance)
            .add(timestamp)
            .add(previous)
            .add(receiverPublicKey)
            .add(amount)
    }

    override fun getType(): AttoBlockType {
        return AttoBlockType.SEND
    }

    override fun getWorkInfo(): ByteArray {
        return previous.value
    }

    override fun getSize(): Int {
        return size
    }

    override fun getHash(): AttoHash {
        return hash
    }

    override fun getPrevious(): AttoHash {
        return previous
    }

    override fun isValid(): Boolean {
        return super.isValid() && height > 0u && amount.raw > 0u && receiverPublicKey != publicKey
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttoSendBlock) return false
        if (!super.equals(other)) return false

        if (previous != other.previous) return false
        if (receiverPublicKey != other.receiverPublicKey) return false
        if (amount != other.amount) return false
        if (hash != other.hash) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + previous.hashCode()
        result = 31 * result + receiverPublicKey.hashCode()
        result = 31 * result + amount.hashCode()
        result = 31 * result + hash.hashCode()
        return result
    }


}

open class AttoReceiveBlock(
    version: UShort,
    publicKey: AttoPublicKey,
    height: ULong,
    balance: AttoAmount,
    timestamp: Instant,
    private val previous: AttoHash,
    private val sendHash: AttoHash,
) : AttoBlock(version = version, publicKey = publicKey, height = height, balance = balance, timestamp = timestamp),
    PreviousSupport, ReceiveSupport {
    private val hash = toByteBuffer().getHash()

    companion object {
        val size = 123

        internal fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoReceiveBlock? {
            if (size > byteBuffer.size) {
                return null
            }

            val blockType = byteBuffer.getBlockType(0)
            if (blockType != AttoBlockType.RECEIVE) {
                throw IllegalArgumentException("Invalid block type: $blockType")
            }

            return AttoReceiveBlock(
                version = byteBuffer.getUShort(),
                publicKey = byteBuffer.getPublicKey(),
                height = byteBuffer.getULong(),
                balance = byteBuffer.getAmount(),
                timestamp = byteBuffer.getInstant(),
                previous = byteBuffer.getHash(),
                sendHash = byteBuffer.getHash()
            )
        }
    }

    override fun toByteBuffer(): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(size)
        return byteBuffer
            .add(getType())
            .add(version)
            .add(publicKey)
            .add(height)
            .add(balance)
            .add(timestamp)
            .add(previous)
            .add(sendHash)
    }

    override fun getType(): AttoBlockType {
        return AttoBlockType.RECEIVE
    }

    override fun getWorkInfo(): ByteArray {
        return previous.value
    }

    override fun getSize(): Int {
        return size
    }

    override fun getHash(): AttoHash {
        return hash
    }

    override fun getPrevious(): AttoHash {
        return previous
    }

    override fun getSendHash(): AttoHash {
        return sendHash
    }

    override fun isValid(): Boolean {
        return super.isValid() && height > 0u && balance > AttoAmount.min
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttoReceiveBlock) return false
        if (!super.equals(other)) return false

        if (previous != other.previous) return false
        if (sendHash != other.sendHash) return false
        if (hash != other.hash) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + previous.hashCode()
        result = 31 * result + sendHash.hashCode()
        result = 31 * result + hash.hashCode()
        return result
    }

}

class AttoOpenBlock(
    version: UShort,
    publicKey: AttoPublicKey,
    balance: AttoAmount,
    timestamp: Instant,
    private val sendHash: AttoHash,
    private val representative: AttoPublicKey,
) : AttoBlock(version = version, publicKey = publicKey, height = 0u, balance = balance, timestamp = timestamp),
    ReceiveSupport, RepresentativeSupport {
    private val hash = toByteBuffer().getHash()

    companion object {
        val size = 115

        internal fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoOpenBlock? {
            if (size > byteBuffer.size) {
                return null
            }

            val blockType = byteBuffer.getBlockType(0)
            if (blockType != AttoBlockType.OPEN) {
                throw IllegalArgumentException("Invalid block type: $blockType")
            }

            return AttoOpenBlock(
                version = byteBuffer.getUShort(),
                publicKey = byteBuffer.getPublicKey(),
                balance = byteBuffer.getAmount(),
                timestamp = byteBuffer.getInstant(),
                sendHash = byteBuffer.getHash(),
                representative = byteBuffer.getPublicKey(),
            )
        }
    }

    override fun toByteBuffer(): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(size)
        return byteBuffer
            .add(getType())
            .add(version)
            .add(publicKey)
            .add(balance)
            .add(timestamp)
            .add(sendHash)
            .add(representative)
    }

    override fun getType(): AttoBlockType {
        return AttoBlockType.OPEN
    }

    override fun getWorkInfo(): ByteArray {
        return publicKey.value
    }

    override fun getSize(): Int {
        return size
    }

    override fun getHash(): AttoHash {
        return hash
    }

    override fun getSendHash(): AttoHash {
        return sendHash
    }

    override fun getRepresentative(): AttoPublicKey {
        return representative
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttoOpenBlock) return false
        if (!super.equals(other)) return false

        if (sendHash != other.sendHash) return false
        if (representative != other.representative) return false
        if (hash != other.hash) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + sendHash.hashCode()
        result = 31 * result + representative.hashCode()
        result = 31 * result + hash.hashCode()
        return result
    }

}


class AttoChangeBlock(
    version: UShort,
    publicKey: AttoPublicKey,
    height: ULong,
    balance: AttoAmount,
    timestamp: Instant,
    private val previous: AttoHash,
    private val representative: AttoPublicKey,
) : AttoBlock(version = version, publicKey = publicKey, height = height, balance = balance, timestamp = timestamp),
    PreviousSupport, RepresentativeSupport {
    private val hash = toByteBuffer().getHash()

    companion object {
        val size = 123

        internal fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoChangeBlock? {
            if (size > byteBuffer.size) {
                return null
            }

            val blockType = byteBuffer.getBlockType(0)
            if (blockType != AttoBlockType.CHANGE) {
                throw IllegalArgumentException("Invalid block type: $blockType")
            }

            return AttoChangeBlock(
                version = byteBuffer.getUShort(),
                publicKey = byteBuffer.getPublicKey(),
                height = byteBuffer.getULong(),
                balance = byteBuffer.getAmount(),
                timestamp = byteBuffer.getInstant(),
                previous = byteBuffer.getHash(),
                representative = byteBuffer.getPublicKey(),
            )
        }
    }

    override fun toByteBuffer(): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(size)
        return byteBuffer
            .add(getType())
            .add(version)
            .add(publicKey)
            .add(balance)
            .add(timestamp)
            .add(previous)
            .add(representative)
    }

    override fun getType(): AttoBlockType {
        return AttoBlockType.CHANGE
    }

    override fun getWorkInfo(): ByteArray {
        return previous.value
    }

    override fun getSize(): Int {
        return size
    }

    override fun getHash(): AttoHash {
        return hash
    }

    override fun getPrevious(): AttoHash {
        return previous
    }

    override fun getRepresentative(): AttoPublicKey {
        return representative
    }

    override fun isValid(): Boolean {
        return super.isValid() && height > 0u
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttoChangeBlock) return false
        if (!super.equals(other)) return false

        if (previous != other.previous) return false
        if (representative != other.representative) return false
        if (hash != other.hash) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + previous.hashCode()
        result = 31 * result + representative.hashCode()
        result = 31 * result + hash.hashCode()
        return result
    }
}


class AccountState(
    val publicKey: AttoPublicKey,
    val version: UShort,
    var height: ULong,
    val representative: AttoPublicKey,
    val balance: AttoAmount,
    val lastHash: AttoHash,
    val lastTransactionTimestamp: Instant,
) {

    companion object {
        fun open(publicKey: AttoPublicKey, representative: AttoPublicKey, sendBlock: AttoSendBlock): AttoOpenBlock {
            if (sendBlock.receiverPublicKey != publicKey) {
                throw IllegalArgumentException("You can't create an Open block for ${sendBlock.getHash()}")
            }
            return AttoOpenBlock(
                version = sendBlock.version,
                publicKey = publicKey,
                balance = sendBlock.amount,
                timestamp = Instant.now(),
                sendHash = sendBlock.getHash(),
                representative = representative,
            )
        }
    }

    fun send(publicKey: AttoPublicKey, amount: AttoAmount): AttoSendBlock {
        if (publicKey == this.publicKey) {
            throw IllegalArgumentException("You can't send money to yourself");
        }
        return AttoSendBlock(
            version = version,
            publicKey = this.publicKey,
            height = height + 1U,
            balance = balance.minus(amount),
            timestamp = Instant.now(),
            previous = lastHash,
            receiverPublicKey = publicKey,
            amount = amount,
        )
    }

    fun receive(sendBlock: AttoSendBlock): AttoReceiveBlock {
        return AttoReceiveBlock(
            version = max(version, sendBlock.version),
            publicKey = publicKey,
            height = height + 1U,
            balance = balance.plus(sendBlock.amount),
            timestamp = Instant.now(),
            previous = lastHash,
            sendHash = sendBlock.getHash(),
        )
    }

    fun change(representative: AttoPublicKey): AttoChangeBlock {
        return AttoChangeBlock(
            version = version,
            publicKey = publicKey,
            height = height + 1U,
            balance = balance,
            timestamp = Instant.now(),
            previous = lastHash,
            representative = representative,
        )
    }
}

open class AttoTransaction(
    val block: AttoBlock,
    val signature: AttoSignature,
    val work: AttoWork
) {

    open fun toByteBuffer(): AttoByteBuffer {
        return AttoByteBuffer(size + block.getSize())
            .add(block.toByteBuffer())
            .add(signature)
            .add(work)
    }

    companion object {
        val size = 72

        fun fromByteBuffer(network: AttoNetwork, byteBuffer: AttoByteBuffer): AttoTransaction? {
            if (size > byteBuffer.size) {
                return null
            }

            val block = AttoBlock.fromByteBuffer(byteBuffer) ?: return null

            if (block.timestamp > Instant.now()) {
                return null
            }

            val transaction = AttoTransaction(
                block = block,
                signature = byteBuffer.getSignature(),
                work = byteBuffer.getWork(),
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

        if (!work.isValid(block.getWorkInfo(), network)) {
            return false
        }


        if (!signature.isValid(block.publicKey, block.getHash().value)) {
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