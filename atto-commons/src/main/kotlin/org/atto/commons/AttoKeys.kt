package org.atto.commons


import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

private class AttoBIP44(val key: ByteArray, val keyParameter: KeyParameter) {

    private constructor(derived: ByteArray) : this(
        derived.copyOfRange(0, 32),
        KeyParameter(derived, 32, 32)
    )

    fun derive(value: Int): AttoBIP44 {
        val hmacSha512 = HMac(SHA512Digest())
        hmacSha512.init(keyParameter)
        hmacSha512.update(0.toByte())

        hmacSha512.update(key, 0, Ed25519.SECRET_KEY_SIZE)

        val indexBytes = ByteArray(4)
        ByteBuffer.wrap(indexBytes).order(ByteOrder.BIG_ENDIAN).putInt(value)
        indexBytes[0] = (indexBytes[0].toInt() or 128.toByte().toInt()).toByte() //hardened

        hmacSha512.update(indexBytes, 0, indexBytes.size)

        val derived = ByteArray(64)
        hmacSha512.doFinal(derived, 0)

        return AttoBIP44(derived)
    }


    companion object {
        fun ed25519(seed: AttoSeed, path: String): AttoPrivateKey {
            val hmacSha512 = HMac(SHA512Digest())

            hmacSha512.init(KeyParameter("ed25519 seed".toByteArray(StandardCharsets.UTF_8)))
            hmacSha512.update(seed.value, 0, seed.value.size)

            val derivated = ByteArray(hmacSha512.macSize)
            hmacSha512.doFinal(derivated, 0)


            val values = path.split("/").asSequence()
                .map { it.trim() }
                .filter { !"M".equals(it, ignoreCase = true) }
                .map { it.replace("'", "").toInt() }
                .toList()

            var bip44 = AttoBIP44(derivated)
            for (v in values) {
                bip44 = bip44.derive(v)
            }

            return AttoPrivateKey(bip44.key)
        }
    }
}

object AttoKeys {
    private val coinType = 1869902945 // "atto".toByteArray().toUInt()

    fun toPrivateKey(seed: AttoSeed, index: UInt): AttoPrivateKey {
        return AttoBIP44.ed25519(seed, "m/44'/${coinType}'/${index}'")
    }

    fun toPublicKey(privateKey: AttoPrivateKey): AttoPublicKey {
        val value = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
        Ed25519.generatePublicKey(privateKey.value, 0, value, 0)
        return AttoPublicKey(value)
    }

}

class AttoPrivateKey(val value: ByteArray) {
    init {
        value.checkLength(32)
    }

    companion object {
        fun generate(): AttoPrivateKey {
            val random = SecureRandom.getInstanceStrong()
            val value = ByteArray(32)
            random.nextBytes(value)
            return AttoPrivateKey(value)
        }
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