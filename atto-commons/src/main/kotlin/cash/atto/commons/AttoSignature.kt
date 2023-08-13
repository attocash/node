package cash.atto.commons

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

data class AttoSignature(val value: ByteArray) {
    companion object {
        val size = 64
        fun parse(value: String): AttoSignature {
            return AttoSignature(value.fromHexToByteArray())
        }

        fun sign(privateKey: AttoPrivateKey, hash: AttoHash): AttoSignature {
            val parameters = Ed25519PrivateKeyParameters(privateKey.value, 0)
            val signer = Ed25519Signer()
            signer.init(true, parameters)
            signer.update(hash.value, 0, hash.value.size)
            return AttoSignature(signer.generateSignature())
        }
    }

    init {
        value.checkLength(size)
    }

    fun isValid(publicKey: AttoPublicKey, hash: AttoHash): Boolean {
        val parameters = Ed25519PublicKeyParameters(publicKey.value, 0)
        val signer = Ed25519Signer()
        signer.init(false, parameters)
        signer.update(hash.value, 0, hash.value.size)
        return signer.verifySignature(value)
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

fun AttoPrivateKey.sign(hash: AttoHash): AttoSignature {
    return AttoSignature.sign(this, hash)
}