package org.atto.commons


import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec


object AttoKeys {

    fun toPrivateKey(seed: AttoSeed, index: UInt): AttoPrivateKey {
        require(index >= 0u)
        return AttoPrivateKey(AttoHashes.hash(32, seed.value, index.toByteArray()))
    }

    fun toPublicKey(privateKey: AttoPrivateKey): AttoPublicKey {
        val key = EdDSAPrivateKeySpec(privateKey.value, ED25519.ED25519_BLAKE2B_CURVES_PEC)
        return AttoPublicKey(key.a.toByteArray())
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