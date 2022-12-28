package org.atto.commons

import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.*
import kotlin.experimental.or

// TODO fix directionary loading
internal object AttoMnemonics {

    fun seedToBip39(seed: AttoSeed, language: AttoMnemonicLanguage): List<String> {
        val byteArraySeed = seed.value;
        val seedLength = byteArraySeed.size * 8
        val seedWithChecksum = byteArraySeed.copyOf(byteArraySeed.size + 1)
        seedWithChecksum[byteArraySeed.size] = checksum(byteArraySeed)
        val checksumLength = seedLength / 32
        val mnemonicSentenceLength = (seedLength + checksumLength) / 11
        val ret: MutableList<String> = ArrayList()
        for (i in 0 until mnemonicSentenceLength) {
            ret.add(language.getWord(next11Bits(seedWithChecksum, i * 11)))
        }
        return ret
    }

    fun bip39ToSeed(mnemonic: List<String?>, language: AttoMnemonicLanguage): ByteArray {
        val seedWithChecksum = extractSeedWithChecksum(mnemonic, language)
        return extractSeed(seedWithChecksum)
    }

    fun isValid(mnemonic: List<String?>, language: AttoMnemonicLanguage): Boolean {
        if (mnemonic.size != 24 || !mnemonic.stream().allMatch { word: String? -> language.wordExists(word) }) {
            return false
        }
        val seedWithChecksum = extractSeedWithChecksum(mnemonic, language)
        val seed = extractSeed(seedWithChecksum)
        val expectedChecksum = checksum(seed)
        return expectedChecksum == seedWithChecksum[seedWithChecksum.size - 1]
    }

    private fun extractSeedWithChecksum(mnemonic: List<String?>, language: AttoMnemonicLanguage): ByteArray {
        val mnemonicSentenceLength = mnemonic.size
        val seedWithChecksumLength = mnemonicSentenceLength * 11
        val seedWithChecksum = ByteArray((seedWithChecksumLength + 7) / 8)
        val mnemonicIndexes: MutableList<Int?> = ArrayList()
        for (word in mnemonic) {
            mnemonicIndexes.add(language.getIndex(word))
        }
        for (i in 0 until mnemonicSentenceLength) {
            writeNext11(seedWithChecksum, mnemonicIndexes[i]!!, i * 11)
        }
        return seedWithChecksum
    }

    private fun extractSeed(seedWithChecksum: ByteArray): ByteArray {
        return seedWithChecksum.copyOf(seedWithChecksum.size - 1)
    }

    private fun checksum(seed: ByteArray): Byte {
        val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(seed)
        val firstByte = hash[0]
        Arrays.fill(hash, 0.toByte())
        return firstByte
    }

    private fun next11Bits(bytes: ByteArray, offset: Int): Int {
        val skip = offset / 8
        val lowerBitsToRemove = 3 * 8 - 11 - offset % 8
        return bytes[skip].toInt() and 0xff shl 16 or (
                bytes[skip + 1].toInt() and 0xff shl 8) or
                if (lowerBitsToRemove < 8) bytes[skip + 2].toInt() and 0xff else 0 shr lowerBitsToRemove and (1 shl 11) - 1
    }

    private fun writeNext11(bytes: ByteArray, value: Int, offset: Int) {
        val skip = offset / 8
        val bitSkip = offset % 8
        run {
            //byte 0
            val firstValue = bytes[skip]
            val toWrite = (value shr 3 + bitSkip).toByte()
            bytes[skip] = (firstValue or toWrite)
        }
        run {
            //byte 1
            val valueInByte = bytes[skip + 1]
            val i = 5 - bitSkip
            val toWrite = (if (i > 0) value shl i else value shr -i).toByte()
            bytes[skip + 1] = (valueInByte or toWrite)
        }
        if (bitSkip >= 6) { //byte 2
            val valueInByte = bytes[skip + 2]
            val toWrite = (value shl 13 - bitSkip).toByte()
            bytes[skip + 2] = (valueInByte or toWrite)
        }
    }

    enum class AttoMnemonicLanguageType(fileName: String) : AttoMnemonicLanguage {
        ENGLISH("english.txt");

        private val dictionary: List<String>
        private val dictionaryMap: Map<String, Int>

        init {
            val classLoader = AttoMnemonics::class.java.classLoader
            val fileLocation = classLoader.getResource(fileName)!!
            dictionary = Files.readAllLines(Paths.get(fileLocation.toURI()))
            dictionaryMap = dictionary.indices.associateBy({ dictionary[it] }) { it }
        }

        override fun getWord(index: Int): String {
            return dictionary[index]
        }

        override fun wordExists(word: String?): Boolean {
            return dictionaryMap.containsKey(word)
        }

        override fun getIndex(word: String?): Int? {
            return dictionaryMap[word]
        }

    }


    interface AttoMnemonicLanguage {
        fun getWord(index: Int): String

        fun wordExists(word: String?): Boolean

        fun getIndex(word: String?): Int?
    }
}