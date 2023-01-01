package org.atto.commons

import org.bitcoinj.crypto.MnemonicCode
import org.bitcoinj.crypto.MnemonicException

class AttoMnemonic(words: List<String>) {
    private val words = words.toList()

    constructor(seed: AttoSeed) : this(MnemonicCode.INSTANCE.toMnemonic(seed.value))
    constructor(words: String) : this(words.split(" "))

    init {
        try {
            MnemonicCode.INSTANCE.check(words);
        } catch (e: MnemonicException) {
            throw AttoMnemonicException("Invalid mnemonic");
        }
    }

    fun toSeed(): AttoSeed {
        return AttoSeed(MnemonicCode.INSTANCE.toEntropy(words))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttoMnemonic) return false

        if (words != other.words) return false

        return true
    }

    override fun hashCode(): Int {
        return words.hashCode()
    }

    override fun toString(): String {
        return "AttoMnemonic(words=${words.size})"
    }


}

class AttoMnemonicException(message: String) : RuntimeException(message)

fun AttoSeed.toMnemonic(): AttoMnemonic {
    return AttoMnemonic(this)
}


