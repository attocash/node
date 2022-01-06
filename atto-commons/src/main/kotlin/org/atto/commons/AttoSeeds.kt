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


@JvmInline
value class AttoSeed(val value: ByteArray) {
    init {
        value.checkLength(32)
    }

    override fun toString(): String {
        return "${this.javaClass.simpleName}(value='${value.size} bytes')"
    }
}
