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
    val hash: AttoHash
    val type: AttoBlockType

    val version: UShort
    val publicKey: AttoPublicKey
    val height: ULong
    val balance: AttoAmount
    val timestamp: Instant

    val serialized: AttoByteBuffer

    companion object {
        fun fromByteBuffer(serializedBlock: AttoByteBuffer): AttoBlock? {
            val type = serializedBlock.getBlockType()
            return when (type) {
                AttoBlockType.SEND -> {
                    AttoSendBlock.fromByteBuffer(serializedBlock)
                }
                AttoBlockType.RECEIVE -> {
                    AttoReceiveBlock.fromByteBuffer(serializedBlock)
                }
                AttoBlockType.OPEN -> {
                    AttoOpenBlock.fromByteBuffer(serializedBlock)
                }
                AttoBlockType.CHANGE -> {
                    AttoChangeBlock.fromByteBuffer(serializedBlock)
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
    override val serialized = toByteBuffer()
    override val hash = serialized.getHash()

    companion object {
        internal fun fromByteBuffer(serializedBlock: AttoByteBuffer): AttoSendBlock? {
            if (AttoBlockType.SEND.size > serializedBlock.size) {
                return null
            }

            val blockType = serializedBlock.getBlockType(0)
            if (blockType != AttoBlockType.SEND) {
                throw IllegalArgumentException("Invalid block type: $blockType")
            }

            return AttoSendBlock(
                version = serializedBlock.getUShort(),
                publicKey = serializedBlock.getPublicKey(),
                height = serializedBlock.getULong(),
                balance = serializedBlock.getAmount(),
                timestamp = serializedBlock.getInstant(),
                previous = serializedBlock.getHash(),
                receiverPublicKey = serializedBlock.getPublicKey(),
                amount = serializedBlock.getAmount(),
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
    override val serialized = toByteBuffer()
    override val hash = serialized.getHash()

    companion object {
        val size = 123

        internal fun fromByteBuffer(serializedBlock: AttoByteBuffer): AttoReceiveBlock? {
            if (AttoBlockType.RECEIVE.size > serializedBlock.size) {
                return null
            }

            val blockType = serializedBlock.getBlockType(0)
            if (blockType != AttoBlockType.RECEIVE) {
                throw IllegalArgumentException("Invalid block type: $blockType")
            }

            return AttoReceiveBlock(
                version = serializedBlock.getUShort(),
                publicKey = serializedBlock.getPublicKey(),
                height = serializedBlock.getULong(),
                balance = serializedBlock.getAmount(),
                timestamp = serializedBlock.getInstant(),
                previous = serializedBlock.getHash(),
                sendHash = serializedBlock.getHash()
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
    override val serialized = toByteBuffer()
    override val hash = serialized.getHash()
    override val height = 1UL

    companion object {
        val size = 115

        internal fun fromByteBuffer(serializedBlock: AttoByteBuffer): AttoOpenBlock? {
            if (AttoBlockType.OPEN.size > serializedBlock.size) {
                return null
            }

            val blockType = serializedBlock.getBlockType(0)
            if (blockType != AttoBlockType.OPEN) {
                throw IllegalArgumentException("Invalid block type: $blockType")
            }

            return AttoOpenBlock(
                version = serializedBlock.getUShort(),
                publicKey = serializedBlock.getPublicKey(),
                balance = serializedBlock.getAmount(),
                timestamp = serializedBlock.getInstant(),
                sendHash = serializedBlock.getHash(),
                representative = serializedBlock.getPublicKey(),
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
    override val serialized = toByteBuffer()
    override val hash = serialized.getHash()

    companion object {
        val size = 123

        internal fun fromByteBuffer(serializedBlock: AttoByteBuffer): AttoChangeBlock? {
            if (AttoBlockType.CHANGE.size > serializedBlock.size) {
                return null
            }

            val blockType = serializedBlock.getBlockType(0)
            if (blockType != AttoBlockType.CHANGE) {
                throw IllegalArgumentException("Invalid block type: $blockType")
            }

            return AttoChangeBlock(
                version = serializedBlock.getUShort(), // 2 2
                publicKey = serializedBlock.getPublicKey(), // 32 34
                height = serializedBlock.getULong(), // 8 42
                balance = serializedBlock.getAmount(), // 8 50
                timestamp = serializedBlock.getInstant(), // 8 58
                previous = serializedBlock.getHash(), // 32 90
                representative = serializedBlock.getPublicKey(), // 32 122
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
            .add(representative)
    }

    override fun getWorkHash(): ByteArray {
        return previous.value
    }

    override fun isValid(): Boolean {
        return super.isValid() && height > 1u
    }
}