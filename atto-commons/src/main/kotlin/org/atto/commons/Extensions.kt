package org.atto.commons

import java.nio.ByteBuffer
import java.time.Instant
import java.util.*


private val HEX_ARRAY = "0123456789ABCDEF".toCharArray()

fun ByteArray.toHex(): String {
    val hexChars = CharArray(this.size * 2)
    for (j in this.indices) {
        val v: Int = this[j].toInt() and 0xFF
        hexChars[j * 2] = HEX_ARRAY.get(v ushr 4)
        hexChars[j * 2 + 1] = HEX_ARRAY.get(v and 0x0F)
    }
    return String(hexChars)
}

fun ByteBuffer.toHex(): String {
    return this.toByteArray().toHex()
}

fun String.fromHexToByteArray(): ByteArray {
    val len = this.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
        i += 2
    }
    return data
}

fun ByteArray.wipe() {
    Arrays.fill(this, 0.toByte())
}

fun UShort.toByteBuffer(): ByteBuffer {
    return ByteBuffer.allocate(2)
        .putShort(this.toShort())
}

fun UShort.toByteArray(): ByteArray {
    return this.toByteBuffer().array()
}

fun ByteBuffer.toUShort(): UShort {
    return this
        .short
        .toUShort()
}

fun ByteArray.toUShort(): UShort {
    return ByteBuffer.wrap(this)
        .short
        .toUShort()
}

fun UInt.toByteBuffer(): ByteBuffer {
    return ByteBuffer.allocate(4)
        .putInt(this.toInt())
}

fun UInt.toByteArray(): ByteArray {
    return this.toByteBuffer().array()
}

fun ByteBuffer.toUInt(): UInt {
    return this
        .int
        .toUInt()
}

fun ByteArray.toUInt(): UInt {
    return ByteBuffer.wrap(this)
        .int
        .toUInt()
}

fun ULong.toByteBuffer(): ByteBuffer {
    return ByteBuffer.allocate(8)
        .putLong(this.toLong())
}

fun ULong.toByteArray(): ByteArray {
    return this.toByteBuffer().array()
}


fun ByteBuffer.toULong(): ULong {
    return this
        .long
        .toULong()
}

fun ByteArray.toULong(): ULong {
    return ByteBuffer.wrap(this)
        .long
        .toULong()
}

fun ByteArray.checkLength(size: Int) {
    require(this.size == size) { "Byte array contains just ${this.size} but should contains have $size" }
}

fun ByteArray.checkMinimalLength(size: Int) {
    require(this.size >= size) { "Byte array contains just ${this.size} but should contains have at least $size" }
}

fun ByteArray.toByteBuffer(): ByteBuffer {
    return ByteBuffer.wrap(this)
}


fun ByteBuffer.toByteArray(): ByteArray {
    return this.array()
}

fun Instant.toByteArray(): ByteArray {
    return this.toEpochMilli().toULong().toByteArray()
}

fun ByteArray.toInstant(): Instant {
    val epochMilli = this.toULong().toLong()
    return Instant.ofEpochMilli(epochMilli)
}