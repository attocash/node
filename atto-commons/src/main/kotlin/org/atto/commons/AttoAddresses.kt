package org.atto.commons


object AttoAddresses {
    val prefix = "atto_"
    val regex = "^$prefix[13][13456789abcdefghijkmnopqrstuwxyz]{59}$".toRegex()

    fun toAddress(publicKey: AttoPublicKey): AttoAddress {
        val checksum = checksum(publicKey.value)

        val encodedPublicKey = AttoAccountEncodes.encode(publicKey.value, 260)
        val encodedChecksum = AttoAccountEncodes.encode(checksum, checksum.size * 8)
        return AttoAddress(prefix + encodedPublicKey + encodedChecksum)
    }

    fun toPublicKey(account: AttoAddress): AttoPublicKey {
        return AttoPublicKey(toPublicKey(account.value))
    }

    private fun toPublicKey(account: String): ByteArray {
        val encodedPublicKey: String = account.substring(5, 57)
        return AttoAccountEncodes.decode(encodedPublicKey, 64)
    }

    fun isValid(account: String): Boolean {
        if (!regex.matches(account)) {
            return false
        }
        val expectedEncodedChecksum = account.substring(account.length - 8)
        val checksum = checksum(toPublicKey(account))
        val encodedChecksum = AttoAccountEncodes.encode(checksum, checksum.size * 8)
        return expectedEncodedChecksum == encodedChecksum
    }

    fun checkValid(value: String) {
        if (!isValid(value)) {
            throw IllegalArgumentException("$value is invalid")
        }
    }

    // TODO: change to little endian
    private fun checksum(publicKey: ByteArray): ByteArray {
        return AttoHashes.hash(5, publicKey)
    }
}

@JvmInline
value class AttoAddress(val value: String) {
    init {
        AttoAddresses.checkValid(value)
    }

    companion object {
        fun parse(value: String): AttoAddress {
            return AttoAddress(value)
        }
    }

    fun toPublicKey(): AttoPublicKey {
        return AttoAddresses.toPublicKey(this)
    }

    override fun toString(): String {
        return value
    }
}

fun AttoPublicKey.toAddress(): AttoAddress {
    return AttoAddresses.toAddress(this)
}