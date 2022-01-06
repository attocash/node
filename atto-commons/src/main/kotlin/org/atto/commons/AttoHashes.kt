package org.atto.commons

import com.rfksystems.blake2b.Blake2b

object AttoHashes {
    fun hash(digestSize: Int, vararg byteArrays: ByteArray): ByteArray {
        val blake2b = Blake2b(null, digestSize, null, null)
        for (byteArray in byteArrays) {
            blake2b.update(byteArray, 0, byteArray.size)
        }
        val output = ByteArray(digestSize)
        blake2b.digest(output, 0)
        return output
    }
}

data class AttoHash(val value: ByteArray) {
    init {
        value.checkLength(32)
    }

    companion object {
        fun parse(value: String): AttoHash {
            return AttoHash(value.fromHexToByteArray())
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttoHash

        if (!value.contentEquals(other.value)) return false

        return true
    }

    override fun hashCode(): Int {
        return value.contentHashCode()
    }

    override fun toString(): String {
        return value.toHex()
    }
}