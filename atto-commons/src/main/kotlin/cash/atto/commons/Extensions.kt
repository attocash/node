package cash.atto.commons

import java.util.*

private val hexFormat = HexFormat.of().withUpperCase()

fun ByteArray.toHex(): String {
    return hexFormat.formatHex(this)
}

fun String.fromHexToByteArray(): ByteArray {
    return hexFormat.parseHex(this)
}

fun ByteArray.checkLength(size: Int) {
    require(this.size == size) { "Byte array contains ${this.size} characters but should contains $size" }
}