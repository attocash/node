package cash.atto.commons

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

class AttoByteBuffer {
    private val byteBuffer: ByteBuffer
    val size: Int

    private var lastIndex = 0

    private constructor(byteBuffer: ByteBuffer) {
        this.byteBuffer = byteBuffer
        this.size = byteBuffer.capacity()
    }

    constructor(size: Int) : this(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN))
    constructor(byteArray: ByteArray) : this(byteArray.size) {
        add(byteArray)
    }

    constructor(hex: String) : this(hex.fromHexToByteArray())

    companion object {
        fun from(byteArray: ByteArray): AttoByteBuffer {
            return AttoByteBuffer(byteArray.size).add(byteArray)
        }
    }

    fun getIndex(): Int {
        return lastIndex
    }

    fun slice(startIndex: Int): AttoByteBuffer {
        val slicedByteBuffer = byteBuffer.slice(startIndex, size - startIndex).order(ByteOrder.LITTLE_ENDIAN)
        return AttoByteBuffer(slicedByteBuffer)
    }

    fun add(byteArray: ByteArray): AttoByteBuffer {
        byteBuffer.put(byteArray)
        return this
    }

    fun add(byteBuffer: AttoByteBuffer): AttoByteBuffer {
        this.byteBuffer.put(byteBuffer.byteBuffer.flip())
        return this
    }

    fun add(byteBuffers: Collection<AttoByteBuffer>): AttoByteBuffer {
        byteBuffers.forEach { add(it) }
        return this
    }

    fun toByteArray(): ByteArray {
        val byteArray = ByteArray(size)

        byteBuffer.get(0, byteArray)

        return byteArray
    }

    fun toHex(): String {
        return toByteArray().toHex()
    }

    fun getByteArray(index: Int, length: Int): ByteArray {
        val byteArray = ByteArray(length)
        this.lastIndex = index + byteArray.size
        byteBuffer.get(index, byteArray)
        return byteArray
    }

    fun toHash(): AttoHash {
        return AttoHash.hash(32, toByteArray())
    }

    fun add(attoHash: AttoHash): AttoByteBuffer {
        byteBuffer.put(attoHash.value)
        return this;
    }

    fun getHash(): AttoHash {
        return getHash(lastIndex)
    }

    fun getHash(index: Int): AttoHash {
        val byteArray = ByteArray(AttoHash.defaultSize)
        byteBuffer.get(index, byteArray)
        this.lastIndex = index + byteArray.size
        return AttoHash(byteArray)
    }

    fun add(attoPublicKey: AttoPublicKey): AttoByteBuffer {
        byteBuffer.put(attoPublicKey.value)
        return this
    }

    fun getPublicKey(): AttoPublicKey {
        return getPublicKey(lastIndex)
    }

    fun getPublicKey(index: Int): AttoPublicKey {
        val byteArray = ByteArray(32)
        byteBuffer.get(index, byteArray)
        this.lastIndex = index + byteArray.size
        return AttoPublicKey(byteArray)
    }

    fun add(byte: Byte): AttoByteBuffer {
        byteBuffer.put(byte)
        return this;
    }

    fun getByte(): Byte {
        return getByte(lastIndex)
    }

    fun getByte(index: Int): Byte {
        this.lastIndex = index + 1
        return byteBuffer.get(index)
    }

    fun add(uByte: UByte): AttoByteBuffer {
        add(uByte.toByte())
        return this;
    }

    fun getUByte(): UByte {
        return getUByte(lastIndex)
    }

    fun getUByte(index: Int): UByte {
        return getByte(index).toUByte()
    }

    fun add(short: Short): AttoByteBuffer {
        byteBuffer.putShort(short)
        return this;
    }

    fun getShort(): Short {
        return getShort(lastIndex)
    }

    fun getShort(index: Int): Short {
        this.lastIndex = index + 2
        return byteBuffer.getShort(index)
    }

    fun add(uShort: UShort): AttoByteBuffer {
        add(uShort.toShort())
        return this;
    }

    fun getUShort(): UShort {
        return getUShort(lastIndex)
    }

    fun getUShort(index: Int): UShort {
        return getShort(index).toUShort()
    }

    fun add(int: Int): AttoByteBuffer {
        byteBuffer.putInt(int)
        return this;
    }

    fun getInt(): Int {
        return getInt(lastIndex)
    }

    fun getInt(index: Int): Int {
        this.lastIndex = index + 4
        return byteBuffer.getInt(index)
    }

    fun add(uInt: UInt): AttoByteBuffer {
        byteBuffer.putInt(uInt.toInt())
        return this;
    }

    fun getUInt(): UInt {
        return getUInt(lastIndex)
    }

    fun getUInt(index: Int): UInt {
        this.lastIndex = index + 4
        return byteBuffer.getInt(index).toUInt()
    }

    fun add(uLong: ULong): AttoByteBuffer {
        byteBuffer.putLong(uLong.toLong())
        return this;
    }

    fun getULong(): ULong {
        return getULong(lastIndex)
    }

    fun getULong(index: Int): ULong {
        this.lastIndex = index + 8
        return byteBuffer.getLong(index).toULong()
    }

    fun add(instant: Instant): AttoByteBuffer {
        byteBuffer.putLong(instant.toEpochMilli())
        return this;
    }

    fun getInstant(): Instant {
        return getInstant(lastIndex)
    }

    fun getInstant(index: Int): Instant {
        this.lastIndex = index + 8
        return Instant.ofEpochMilli(byteBuffer.getLong(index))
    }

    fun add(attoBlockType: AttoBlockType): AttoByteBuffer {
        byteBuffer.put(attoBlockType.code.toByte())
        return this
    }

    fun getBlockType(): AttoBlockType {
        return getBlockType(lastIndex)
    }

    fun getBlockType(index: Int): AttoBlockType {
        this.lastIndex = index + 1
        return AttoBlockType.from(byteBuffer.get(index).toUByte())
    }

    fun add(network: AttoNetwork): AttoByteBuffer {
        byteBuffer.put(network.environment.toByteArray(Charsets.UTF_8))
        return this
    }

    fun getNetwork(): AttoNetwork {
        return getNetwork(lastIndex)
    }

    fun getNetwork(index: Int): AttoNetwork {
        return AttoNetwork.from(getByteArray(index, 3).toString(Charsets.UTF_8))
    }

    fun add(attoAmount: AttoAmount): AttoByteBuffer {
        return add(attoAmount.raw)
    }

    fun getAmount(): AttoAmount {
        return getAmount(lastIndex)
    }

    fun getAmount(index: Int): AttoAmount {
        return AttoAmount(getULong(index))
    }

    fun add(attoSignature: AttoSignature): AttoByteBuffer {
        byteBuffer.put(attoSignature.value)
        return this;
    }

    fun getSignature(): AttoSignature {
        return getSignature(lastIndex)
    }

    fun getSignature(index: Int): AttoSignature {
        val byteArray = ByteArray(AttoSignature.size)
        byteBuffer.get(index, byteArray)
        this.lastIndex = index + byteArray.size
        return AttoSignature(byteArray)
    }

    fun add(attoWork: AttoWork): AttoByteBuffer {
        byteBuffer.put(attoWork.value)
        return this;
    }

    fun getWork(): AttoWork {
        return getWork(lastIndex)
    }

    fun getWork(index: Int): AttoWork {
        val byteArray = ByteArray(AttoWork.size)
        byteBuffer.get(index, byteArray)
        this.lastIndex = index + byteArray.size
        return AttoWork(byteArray)
    }

    fun add(inetSocketAddress: InetSocketAddress): AttoByteBuffer {
        val address = inetSocketAddress.address.address
        val port = inetSocketAddress.port.toUShort()

        val byteArray = ByteArray(16)
        if (address.size == 16) {
            System.arraycopy(address, 0, byteArray, 0, 16)
        } else {
            byteArray[10] = -1
            byteArray[11] = -1
            System.arraycopy(address, 0, byteArray, 12, 4)
        }

        add(byteArray)
        add(port)

        return this;
    }

    fun getInetSocketAddress(): InetSocketAddress {
        return getInetSocketAddress(lastIndex)
    }

    fun getInetSocketAddress(index: Int): InetSocketAddress {
        val address = InetAddress.getByAddress(getByteArray(index, 16))
        val port = getUShort().toInt()

        return InetSocketAddress(address, port)
    }

    override fun toString(): String {
        return "AttoByteBuffer(${this.toHex()})"
    }

}

fun ByteArray.toAttoByteBuffer(): AttoByteBuffer {
    return AttoByteBuffer(this)
}

fun String.fromHexToAttoByteBuffer(): AttoByteBuffer {
    return AttoByteBuffer(this)
}

fun Instant.toByteArray(): ByteArray {
    return AttoByteBuffer(8)
        .add(this)
        .toByteArray()
}

fun ByteArray.toInstant(): Instant {
    return AttoByteBuffer(this).getInstant()
}

fun UShort.toByteArray(): ByteArray {
    return AttoByteBuffer(2)
        .add(this)
        .toByteArray()
}

fun ByteArray.toUShort(): UShort {
    return AttoByteBuffer(this).getUShort()
}

fun UInt.toByteArray(): ByteArray {
    return AttoByteBuffer(4)
        .add(this)
        .toByteArray()
}

fun ByteArray.toUInt(): UInt {
    return AttoByteBuffer(this).getUInt()
}

fun ULong.toByteArray(): ByteArray {
    return AttoByteBuffer(8)
        .add(this)
        .toByteArray()
}

fun ByteArray.toULong(): ULong {
    return AttoByteBuffer(this).getULong()
}