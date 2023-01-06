package org.atto.commons

import java.math.BigInteger
import java.util.*

private fun leftPad(binary: String, size: Int): String {
    if (binary.length >= size) {
        return binary
    }
    val builder = StringBuilder()
    while (binary.length + builder.length < size) {
        builder.append("0")
    }
    return builder.append(binary).toString()
}

object Dictionary {
    private val characterMap = HashMap<String, String>()
    private val binaryMap = HashMap<Char, String>()

    init {
        val alphabet = "13456789abcdefghijkmnopqrstuwxyz".toCharArray()
        for (i in alphabet.indices) {
            val binary: String = leftPad(Integer.toBinaryString(i), 5)
            characterMap[binary] = alphabet[i].toString()
            binaryMap[alphabet[i]] = binary
        }
    }

    fun getCharacter(binary: String): String? {
        return characterMap[binary]
    }

    fun getBinary(character: Char): String? {
        return binaryMap[character]
    }
}

fun decode(encoded: String, size: Int): ByteArray {
    val binaryPublicKey = decodeToBinary(encoded)
    val hexPublicKey = leftPad(toHex(binaryPublicKey), size)
    return hexPublicKey.fromHexToByteArray()
}

private fun decodeToBinary(encoded: String): String {
    val sb = StringBuilder()
    for (element in encoded) {
        sb.append(Dictionary.getBinary(element))
    }
    return sb.toString()
}

fun encode(decoded: ByteArray, size: Int): String {
    val binary = leftPad(toBinary(decoded.toHex()), size)
    return encode(binary)
}

private fun encode(decoded: String): String {
    val codeSize = 5
    val builder = StringBuilder()
    var i = 0
    while (i < decoded.length) {
        builder.append(Dictionary.getCharacter(decoded.substring(i, i + codeSize)))
        i += codeSize
    }
    return builder.toString()
}

private fun toBinary(hex: String): String {
    return BigInteger(hex, 16).toString(2)
}

private fun toHex(binary: String): String {
    val b = BigInteger(binary, 2)
    return b.toString(16).uppercase(Locale.ENGLISH)
}

@JvmInline
value class AttoAddress(val value: String) {
    init {
        if (!isValid(value)) {
            throw IllegalArgumentException("$value is invalid")
        }
    }

    constructor(publicKey: AttoPublicKey) : this(toAddress(publicKey))

    companion object {
        private val prefix = "atto_"
        private val regex = "^${prefix}[13][13456789abcdefghijkmnopqrstuwxyz]{59}$".toRegex()
        private fun checksum(publicKey: AttoPublicKey): AttoHash {
            return AttoHash.hash(5, publicKey.value)
        }

        private fun toPublicKey(value: String): AttoPublicKey {
            val encodedPublicKey: String = value.substring(5, 57)
            return AttoPublicKey(decode(encodedPublicKey, 64))
        }

        fun isValid(value: String): Boolean {
            if (!regex.matches(value)) {
                return false
            }
            val expectedEncodedChecksum = value.substring(value.length - 8)
            val checksum = checksum(toPublicKey(value))
            val encodedChecksum = encode(checksum.value, checksum.size * 8)
            return expectedEncodedChecksum == encodedChecksum
        }

        fun toAddress(publicKey: AttoPublicKey): String {
            val checksum = checksum(publicKey)

            val encodedPublicKey = encode(publicKey.value, 260)
            val encodedChecksum = encode(checksum.value, checksum.size * 8)
            return prefix + encodedPublicKey + encodedChecksum
        }

        fun parse(value: String): AttoAddress {
            return AttoAddress(value)
        }
    }

    fun toPublicKey(): AttoPublicKey {
        return toPublicKey(this.value)
    }

    override fun toString(): String {
        return value
    }
}

fun AttoPublicKey.toAddress(): AttoAddress {
    return AttoAddress(this)
}