package org.atto.commons

import java.security.SecureRandom


object AttoSeeds {
    private val random = SecureRandom.getInstanceStrong()

    fun generateSeed(): AttoSeed {
        val seed = ByteArray(32)
        random.nextBytes(seed)
        return AttoSeed(seed)
    }
}


data class AttoSeed(val value: ByteArray) {
    init {
        value.checkLength(32)
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
