package org.atto.commons


import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters


object AttoKeys {

    fun toPrivateKey(seed: AttoSeed, index: UInt): AttoPrivateKey {
        require(index >= 0u)
        return AttoPrivateKey(AttoHashes.hash(32, seed.value, index.toByteArray()).value)
    }

    fun toPublicKey(privateKey: AttoPrivateKey): AttoPublicKey {
        val parameters = Ed25519PrivateKeyParameters(privateKey.value, 0)
        val publicKey = parameters.generatePublicKey()
        return AttoPublicKey(publicKey.encoded)
    }

}

class AttoPrivateKey(val value: ByteArray) {
    init {
        value.checkLength(32)
    }

    fun toPublicKey(): AttoPublicKey {
        return AttoKeys.toPublicKey(this)
    }

    override fun toString(): String {
        return "${value.size} bytes"
    }
}


data class AttoPublicKey(val value: ByteArray) {
    init {
        value.checkLength(32)
    }

    companion object {
        fun parse(value: String): AttoPublicKey {
            return AttoPublicKey(value.fromHexToByteArray())
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttoPublicKey

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

fun AttoSeed.toPrivateKey(index: UInt): AttoPrivateKey {
    return AttoKeys.toPrivateKey(this, index)
}