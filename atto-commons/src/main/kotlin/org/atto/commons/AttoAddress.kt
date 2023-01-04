package org.atto.commons

@JvmInline
value class AttoAddress(val value: String) {
    init {
        if (!isValid(value)) {
            throw IllegalArgumentException("$value is invalid")
        }
    }

    companion object {
        private val prefix = "atto_"
        private val regex = "^${prefix}[13][13456789abcdefghijkmnopqrstuwxyz]{59}$".toRegex()
        private fun checksum(publicKey: AttoPublicKey): AttoHash {
            return AttoHashes.hash(5, publicKey.value)
        }

        private fun toPublicKey(account: String): AttoPublicKey {
            val encodedPublicKey: String = account.substring(5, 57)
            return AttoPublicKey(AttoAccountEncodes.decode(encodedPublicKey, 64))
        }

        fun isValid(value: String): Boolean {
            if (!regex.matches(value)) {
                return false
            }
            val expectedEncodedChecksum = value.substring(value.length - 8)
            val checksum = checksum(toPublicKey(value))
            val encodedChecksum = AttoAccountEncodes.encode(checksum.value, checksum.size * 8)
            return expectedEncodedChecksum == encodedChecksum
        }

        fun toAddress(publicKey: AttoPublicKey): AttoAddress {
            val checksum = checksum(publicKey)

            val encodedPublicKey = AttoAccountEncodes.encode(publicKey.value, 260)
            val encodedChecksum = AttoAccountEncodes.encode(checksum.value, checksum.size * 8)
            return AttoAddress(prefix + encodedPublicKey + encodedChecksum)
        }

        fun parse(value: String): AttoAddress {
            return AttoAddress(value)
        }
    }

    fun toPublicKey(): AttoPublicKey {
        return toPublicKey(this.value)
    }

    override fun toString(): String {
        return value
    }
}

fun AttoPublicKey.toAddress(): AttoAddress {
    return AttoAddress.toAddress(this)
}