package org.atto.commons

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.charset.StandardCharsets
import java.text.Normalizer

data class AttoSeed(val value: ByteArray) {
    init {
        value.checkLength(64)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttoSeed) return false

        if (!value.contentEquals(other.value)) return false

        return true
    }

    override fun hashCode(): Int {
        return value.contentHashCode()
    }

    override fun toString(): String {
        return "${this.javaClass.simpleName}(value='${value.size} bytes')"
    }
}

/**
 * BIP39 seed generation
 */
fun AttoMnemonic.toSeed(passphrase: String = ""): AttoSeed {
    val salt = Normalizer.normalize("mnemonic$passphrase", Normalizer.Form.NFKD)

    val pbkdf2 = PKCS5S2ParametersGenerator(SHA512Digest())
    pbkdf2.init(
        words.joinToString(" ").toByteArray(StandardCharsets.UTF_8),
        salt.toByteArray(StandardCharsets.UTF_8),
        2048
    )

    val key = pbkdf2.generateDerivedParameters(512) as KeyParameter
    return AttoSeed(key.key)
}
