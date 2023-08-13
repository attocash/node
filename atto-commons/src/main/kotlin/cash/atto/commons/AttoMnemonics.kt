package cash.atto.commons

import org.bouncycastle.crypto.digests.SHA256Digest
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.security.SecureRandom


private val fileLocation = AttoMnemonic::class.java.classLoader.getResource("mnemonics/english.txt")!!
private val dictionary = Files.readAllLines(Paths.get(fileLocation.toURI()))
private val dictionaryMap = dictionary.indices.associateBy({ dictionary[it] }) { it }


private fun toEntropyWithChecksum(words: List<String>): ByteArray {
    val buffer = ByteBuffer.allocate(33)

    var scratch = 0
    var offset = 0
    for (word in words) {
        val index = dictionaryMap[word]!!
        scratch = scratch shl 11
        scratch = scratch or index
        offset += 11
        while (offset >= 8) {
            buffer.put((scratch shr offset - 8).toByte())
            offset -= 8
        }
    }

    if (offset != 0) {
        buffer.put((scratch shl offset).toByte())
    }

    return buffer.array()
}

private fun checksum(entropy: ByteArray): Byte {
    val digest = SHA256Digest()
    digest.update(entropy, 0, 32)

    val checksum = ByteArray(digest.digestSize)
    digest.doFinal(checksum, 0)
    return checksum[0]
}


class AttoMnemonic {
    val words: List<String>

    constructor(words: List<String>) {
        if (words.size != 24) {
            throw AttoMnemonicException("Mnemonic should have 24 words")
        }

        for (word in words) {
            if (!dictionary.contains(word)) {
                throw AttoMnemonicException("The word $word is not part of english mnemonic dictionary")
            }
        }

        val entropyWithChecksum = toEntropyWithChecksum(words)
        val checksum = checksum(entropyWithChecksum)

        if (entropyWithChecksum[32] != checksum) {
            throw AttoMnemonicException("Invalid mnemonic.")
        }

        this.words = words.toList()
    }

    constructor(words: String) : this(words.split(" "))

    constructor(entropy: ByteArray) {
        val words = ArrayList<String>(24)

        val copy = entropy.copyOf()
        copy[32] = checksum(entropy)
        var scratch = 0
        var offset = 0
        for (b in copy) {
            scratch = scratch shl 8
            scratch = scratch or (b.toInt() and 0xFF)
            offset += 8
            if (offset >= 11) {
                val index: Int = scratch shr offset - 11 and 0x7FF
                offset -= 11
                words.add(dictionary[index])
            }
        }
        this.words = words.toList()
    }

    companion object {
        fun generate(): AttoMnemonic {
            val random = SecureRandom.getInstanceStrong()
            val entropy = ByteArray(33)
            random.nextBytes(entropy)
            return AttoMnemonic(entropy)
        }
    }

    fun toEntropy(): ByteArray {
        return toEntropyWithChecksum(words).sliceArray(0 until 33)
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


