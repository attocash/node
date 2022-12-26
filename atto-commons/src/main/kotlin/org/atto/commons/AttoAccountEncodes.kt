package org.atto.commons

import java.math.BigInteger
import java.util.*


internal object AttoAccountEncodes {
    private val alphabet = Alphabet()

    fun decode(encoded: String, size: Int): ByteArray {
        val binaryPublicKey = decodeToBinary(encoded)
        val hexPublicKey = leftPad(toHex(binaryPublicKey), size)
        return hexPublicKey.fromHexToByteArray()
    }

    private fun decodeToBinary(encoded: String): String {
        val sb = StringBuilder()
        for (element in encoded) {
            sb.append(alphabet.getBinary(element))
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
            builder.append(alphabet.getCharacter(decoded.substring(i, i + codeSize)))
            i += codeSize
        }
        return builder.toString()
    }

    private fun toBinary(hex: String): String {
        val value = BigInteger(hex, 16).toString(2)
        val formatPad = "%" + hex.length * 4 + "s"
        return String.format(formatPad, value).replace(" ", "")
    }

    private fun toHex(binary: String): String {
        val b = BigInteger(binary, 2)
        return b.toString(16).uppercase(Locale.ENGLISH)
    }

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

    private class Alphabet {
        private val characterMap = HashMap<String, String>()
        private val binaryMap = HashMap<Char, String>()

        init {
            for (i in ACCOUNT_MAP.indices) {
                val binary: String = leftPad(Integer.toBinaryString(i), 5)
                characterMap[binary] = ACCOUNT_MAP[i].toString()
                binaryMap[ACCOUNT_MAP[i]] = binary
            }
        }

        fun getCharacter(binary: String): String? {
            return characterMap[binary]
        }

        fun getBinary(character: Char): String? {
            return binaryMap[character]
        }

        companion object {
            private val ACCOUNT_MAP = "13456789abcdefghijkmnopqrstuwxyz".toCharArray()
        }
    }
}