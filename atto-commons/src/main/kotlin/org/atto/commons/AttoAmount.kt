package org.atto.commons

enum class AttoUnit(val prefix: String, val multiplier: AttoAmount) {
    ATTO("atto", AttoAmount(1_000_000_000u)),
    RAW("raw", AttoAmount(1u))
}

@JvmInline
value class AttoAmount(val raw: ULong) {

    companion object {
        val max = AttoAmount(18_000_000_000_000_000_000u)
        val min = AttoAmount(0u)

        fun from(byteArray: ByteArray): AttoAmount {
            return AttoAmount(byteArray.toULong())
        }
    }

    operator fun plus(amount: AttoAmount): AttoAmount {
        return AttoAmount(raw + amount.raw)
    }

    operator fun minus(amount: AttoAmount): AttoAmount {
        return AttoAmount(raw - amount.raw)
    }

    fun toByteArray(): ByteArray {
        return raw.toByteArray()
    }

    operator fun compareTo(amount: AttoAmount): Int {
        return this.raw.compareTo(amount.raw)
    }
}