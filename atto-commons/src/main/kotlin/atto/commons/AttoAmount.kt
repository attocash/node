package atto.commons

import java.math.BigDecimal
import java.math.BigInteger

enum class AttoUnit(val prefix: String, internal val multiplier: BigDecimal) {
    ATTO("atto", BigDecimal(1_000_000_000)),
    RAW("raw", BigDecimal(1))
}

@JvmInline
value class AttoAmount(val raw: ULong) : Comparable<AttoAmount> {
    init {
        if (raw > MAX_RAW) {
            throw IllegalStateException("$raw exceeds the max amount of $MAX_RAW")
        }
    }

    companion object {
        private val MAX_RAW = 18_000_000_000_000_000_000UL
        val MAX = AttoAmount(MAX_RAW)
        private val MIN_RAW = 0UL
        val MIN = AttoAmount(MIN_RAW)

        fun from(unit: AttoUnit, bigDecimal: BigDecimal): AttoAmount {
            return bigDecimal.multiply(unit.multiplier).toAttoAmount()
        }
    }

    fun toBigDecimal(unit: AttoUnit): BigDecimal {
        return BigDecimal(raw.toString()).divide(unit.multiplier)
    }

    operator fun plus(amount: AttoAmount): AttoAmount {
        val total = raw + amount.raw
        if (total < raw || total < amount.raw) {
            throw IllegalStateException("ULong overflow")
        }
        return AttoAmount(total)
    }

    operator fun minus(amount: AttoAmount): AttoAmount {
        val total = raw - amount.raw
        if (total > raw) {
            throw IllegalStateException("ULong underflow")
        }
        return AttoAmount(total)
    }

    override operator fun compareTo(other: AttoAmount): Int {
        return this.raw.compareTo(other.raw)
    }

    override fun toString(): String {
        return "$raw"
    }


}

fun ULong.toAttoAmount(): AttoAmount {
    return AttoAmount(this)
}

fun String.toAttoAmount(): AttoAmount {
    return AttoAmount(this.toULong())
}

fun BigInteger.toAttoAmount(): AttoAmount {
    return AttoAmount(toString().toULong())
}

fun BigDecimal.toAttoAmount(): AttoAmount {
    return AttoAmount(toString().toULong())
}