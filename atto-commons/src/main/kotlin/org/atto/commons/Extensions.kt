package org.atto.commons

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.*


// TODO: Migrate ByteArray extensions to AttoByteBuffer


private val hexFormat = HexFormat.of().withUpperCase()

fun ByteArray.toHex(): String {
    return hexFormat.formatHex(this)
}

fun ByteBuffer.toHex(): String {
    return this.toByteArray().toHex()
}

fun AttoByteBuffer.toHex(): String {
    return this.toByteArray().toHex()
}

fun String.fromHexToByteArray(): ByteArray {
    return hexFormat.parseHex(this)
}

fun String.fromHexToAttoByteBuffer(): AttoByteBuffer {
    return AttoByteBuffer(this.fromHexToByteArray())
}

fun ByteArray.wipe() {
    Arrays.fill(this, 0.toByte())
}

fun UShort.toByteBuffer(): ByteBuffer {
    return ByteBuffer.allocate(2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(this.toShort())
}

fun UShort.toByteArray(): ByteArray {
    return this.toByteBuffer().array()
}


fun ByteArray.toUShort(): UShort {
    return ByteBuffer.wrap(this)
        .order(ByteOrder.LITTLE_ENDIAN)
        .short
        .toUShort()
}

fun UInt.toByteBuffer(): ByteBuffer {
    return ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(this.toInt())
}

fun UInt.toByteArray(): ByteArray {
    return this.toByteBuffer().array()
}

fun ULong.toByteBuffer(): ByteBuffer {
    return ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
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
    return AttoByteBuffer(this)
        .getULong()
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