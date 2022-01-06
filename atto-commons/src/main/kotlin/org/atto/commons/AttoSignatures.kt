package org.atto.commons

import com.rfksystems.blake2b.Blake2b
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest

object AttoSignatures {

    fun sign(privateKey: AttoPrivateKey, hash: ByteArray): AttoSignature {
        val edDSAPrivateKeySpec = EdDSAPrivateKeySpec(privateKey.value, ED25519.ED25519_BLAKE2B_CURVES_PEC)
        val edDSAEngine = EdDSAEngine(MessageDigest.getInstance(Blake2b.BLAKE2_B_512))
        val edDSAPrivateKey = EdDSAPrivateKey(edDSAPrivateKeySpec)
        edDSAEngine.initSign(edDSAPrivateKey)
        edDSAEngine.setParameter(EdDSAEngine.ONE_SHOT_MODE)
        edDSAEngine.update(hash)
        return AttoSignature(edDSAEngine.sign())
    }

    fun isValid(publicKey: AttoPublicKey, signature: AttoSignature, hash: ByteArray): Boolean {
        val edDSAPublicKeySpec = EdDSAPublicKeySpec(publicKey.value, ED25519.ED25519_BLAKE2B_CURVES_PEC)
        val edDSAEngine = EdDSAEngine(MessageDigest.getInstance(Blake2b.BLAKE2_B_512))
        val edDSAPublicKey = EdDSAPublicKey(edDSAPublicKeySpec)
        edDSAEngine.initVerify(edDSAPublicKey)
        edDSAEngine.setParameter(EdDSAEngine.ONE_SHOT_MODE)
        edDSAEngine.update(hash)
        return edDSAEngine.verify(signature.value)
    }

}

data class AttoSignature(val value: ByteArray) {
    companion object {
        val size = 64

        fun parse(value: String): AttoSignature {
            return AttoSignature(value.fromHexToByteArray())
        }
    }

    init {
        value.checkLength(size)
    }

    fun isValid(publicKey: AttoPublicKey, hash: ByteArray): Boolean {
        return AttoSignatures.isValid(publicKey, this, hash)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttoSignature

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


fun AttoPrivateKey.sign(hash: ByteArray): AttoSignature {
    return AttoSignatures.sign(this, hash)
}