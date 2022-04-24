package org.atto.commons

import java.time.Instant

val maxVersion: UShort = 0U

enum class AttoBlockType(val code: UByte, val size: Int) {
    OPEN(0u, 115),
    RECEIVE(1u, 123),
    SEND(2u, 131),
    CHANGE(3u, 123),

    UNKNOWN(UByte.MAX_VALUE, 0);

    companion object {
        private val map = values().associateBy(AttoBlockType::code)
        fun from(code: UByte): AttoBlockType {
            return map.getOrDefault(code, UNKNOWN)
        }
    }
}

interface AttoBlock {
    val version: UShort
    val publicKey: AttoPublicKey
    val height: ULong
    val balance: AttoAmount
    val timestamp: Instant

    val type: AttoBlockType
    val byteBuffer: AttoByteBuffer
    val hash: AttoHash

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

    fun getWorkHash(): ByteArray // ugly name rename

    fun isValid(): Boolean {
        return version <= maxVersion && timestamp <= Instant.now()
    }
}

interface PreviousSupport {
    val height: ULong
    val previous: AttoHash
}

interface ReceiveSupportBlock {
    val balance: AttoAmount
    val sendHash: AttoHash
}

interface RepresentativeSupportBlock {
    val representative: AttoPublicKey
}

data class AttoSendBlock(
    override val version: UShort,
    override val publicKey: AttoPublicKey,
    override val height: ULong,
    override val balance: AttoAmount,
    override val timestamp: Instant,
    override val previous: AttoHash,
    val receiverPublicKey: AttoPublicKey,
    val amount: AttoAmount,
) : AttoBlock, PreviousSupport {
    override val type = AttoBlockType.SEND
    override val byteBuffer = toByteBuffer()
    override val hash = byteBuffer.getHash()

    companion object {
        internal fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoSendBlock? {
            if (AttoBlockType.SEND.size > byteBuffer.size) {
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

    private fun toByteBuffer(): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(type.size)
        return byteBuffer
            .add(type)
            .add(version)
            .add(publicKey)
            .add(height)
            .add(balance)
            .add(timestamp)
            .add(previous)
            .add(receiverPublicKey)
            .add(amount)
    }

    override fun getWorkHash(): ByteArray {
        return previous.value
    }

    override fun isValid(): Boolean {
        return super.isValid() && height > 1u && amount.raw > 0u && receiverPublicKey != publicKey
    }
}

data class AttoReceiveBlock(
    override val version: UShort,
    override val publicKey: AttoPublicKey,
    override val height: ULong,
    override val balance: AttoAmount,
    override val timestamp: Instant,
    override val previous: AttoHash,
    override val sendHash: AttoHash,
) : AttoBlock, PreviousSupport, ReceiveSupportBlock {

    override val type = AttoBlockType.RECEIVE
    override val byteBuffer = toByteBuffer()
    override val hash = byteBuffer.getHash()

    companion object {
        val size = 123

        internal fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoReceiveBlock? {
            if (AttoBlockType.RECEIVE.size > byteBuffer.size) {
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

    private fun toByteBuffer(): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(size)
        return byteBuffer
            .add(type)
            .add(version)
            .add(publicKey)
            .add(height)
            .add(balance)
            .add(timestamp)
            .add(previous)
            .add(sendHash)
    }

    override fun getWorkHash(): ByteArray {
        return previous.value
    }

    override fun isValid(): Boolean {
        return super.isValid() && height > 1u && balance > AttoAmount.min
    }
}

data class AttoOpenBlock(
    override val version: UShort,
    override val publicKey: AttoPublicKey,
    override val balance: AttoAmount,
    override val timestamp: Instant,
    override val sendHash: AttoHash,
    override val representative: AttoPublicKey,
) : AttoBlock, ReceiveSupportBlock, RepresentativeSupportBlock {

    override val type = AttoBlockType.OPEN
    override val byteBuffer = toByteBuffer()
    override val hash = byteBuffer.getHash()
    override val height = 1UL

    companion object {
        val size = 115

        internal fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoOpenBlock? {
            if (AttoBlockType.OPEN.size > byteBuffer.size) {
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

    private fun toByteBuffer(): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(size)
        return byteBuffer
            .add(type)
            .add(version)
            .add(publicKey)
            .add(balance)
            .add(timestamp)
            .add(sendHash)
            .add(representative)
    }

    override fun getWorkHash(): ByteArray {
        return publicKey.value
    }
}


data class AttoChangeBlock(
    override val version: UShort,
    override val publicKey: AttoPublicKey,
    override val height: ULong,
    override val balance: AttoAmount,
    override val timestamp: Instant,
    override val previous: AttoHash,
    override val representative: AttoPublicKey,
) : AttoBlock, PreviousSupport, RepresentativeSupportBlock {
    override val type = AttoBlockType.CHANGE
    override val byteBuffer = toByteBuffer()
    override val hash = byteBuffer.getHash()

    companion object {
        val size = 123

        internal fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoChangeBlock? {
            if (AttoBlockType.CHANGE.size > byteBuffer.size) {
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

    private fun toByteBuffer(): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(size)
        return byteBuffer
            .add(type)
            .add(version)
            .add(publicKey)
            .add(balance)
            .add(timestamp)
            .add(previous)
            .add(representative)
    }

    override fun getWorkHash(): ByteArray {
        return previous.value
    }

    override fun isValid(): Boolean {
        return super.isValid() && height > 1u
    }
}