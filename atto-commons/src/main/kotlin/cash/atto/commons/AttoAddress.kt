package cash.atto.commons

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
            val binary: String = cash.atto.commons.leftPad(Integer.toBinaryString(i), 5)
            cash.atto.commons.Dictionary.characterMap[binary] = alphabet[i].toString()
            cash.atto.commons.Dictionary.binaryMap[alphabet[i]] = binary
        }
    }

    fun getCharacter(binary: String): String? {
        return cash.atto.commons.Dictionary.characterMap[binary]
    }

    fun getBinary(character: Char): String? {
        return cash.atto.commons.Dictionary.binaryMap[character]
    }
}

fun decode(encoded: String, size: Int): ByteArray {
    val binaryPublicKey = cash.atto.commons.decodeToBinary(encoded)
    val hexPublicKey = cash.atto.commons.leftPad(cash.atto.commons.toHex(binaryPublicKey), size)
    return hexPublicKey.fromHexToByteArray()
}

private fun decodeToBinary(encoded: String): String {
    val sb = StringBuilder()
    for (element in encoded) {
        sb.append(cash.atto.commons.Dictionary.getBinary(element))
    }
    return sb.toString()
}

fun encode(decoded: ByteArray, size: Int): String {
    val binary = cash.atto.commons.leftPad(cash.atto.commons.toBinary(decoded.toHex()), size)
    return cash.atto.commons.encode(binary)
}

private fun encode(decoded: String): String {
    val codeSize = 5
    val builder = StringBuilder()
    var i = 0
    while (i < decoded.length) {
        builder.append(cash.atto.commons.Dictionary.getCharacter(decoded.substring(i, i + codeSize)))
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
        if (!cash.atto.commons.AttoAddress.Companion.isValid(value)) {
            throw IllegalArgumentException("$value is invalid")
        }
    }

    constructor(publicKey: cash.atto.commons.AttoPublicKey) : this(
        cash.atto.commons.AttoAddress.Companion.toAddress(
            publicKey
        )
    )

    companion object {
        private val prefix = "atto_"
        private val regex =
            "^${cash.atto.commons.AttoAddress.Companion.prefix}[13][13456789abcdefghijkmnopqrstuwxyz]{59}$".toRegex()

        private fun checksum(publicKey: cash.atto.commons.AttoPublicKey): cash.atto.commons.AttoHash {
            return cash.atto.commons.AttoHash.Companion.hash(5, publicKey.value)
        }

        private fun toPublicKey(value: String): cash.atto.commons.AttoPublicKey {
            val encodedPublicKey: String = value.substring(5, 57)
            return cash.atto.commons.AttoPublicKey(cash.atto.commons.decode(encodedPublicKey, 64))
        }

        fun isValid(value: String): Boolean {
            if (!cash.atto.commons.AttoAddress.Companion.regex.matches(value)) {
                return false
            }
            val expectedEncodedChecksum = value.substring(value.length - 8)
            val checksum =
                cash.atto.commons.AttoAddress.Companion.checksum(
                    cash.atto.commons.AttoAddress.Companion.toPublicKey(
                        value
                    )
                )
            val encodedChecksum = cash.atto.commons.encode(checksum.value, checksum.size * 8)
            return expectedEncodedChecksum == encodedChecksum
        }

        fun toAddress(publicKey: cash.atto.commons.AttoPublicKey): String {
            val checksum = cash.atto.commons.AttoAddress.Companion.checksum(publicKey)

            val encodedPublicKey = cash.atto.commons.encode(publicKey.value, 260)
            val encodedChecksum = cash.atto.commons.encode(checksum.value, checksum.size * 8)
            return cash.atto.commons.AttoAddress.Companion.prefix + encodedPublicKey + encodedChecksum
        }

        fun parse(value: String): cash.atto.commons.AttoAddress {
            return cash.atto.commons.AttoAddress(value)
        }
    }

    fun toPublicKey(): cash.atto.commons.AttoPublicKey {
        return cash.atto.commons.AttoAddress.Companion.toPublicKey(this.value)
    }

    override fun toString(): String {
        return value
    }
}

fun cash.atto.commons.AttoPublicKey.toAddress(): cash.atto.commons.AttoAddress {
    return cash.atto.commons.AttoAddress(this)
}