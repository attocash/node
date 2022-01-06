package org.atto.commons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

internal class AttoMnemonicsTest {
    private val seed = AttoSeed("8FAB00B5EBFE073D5D8927345DEC6F397A49B73966A040148270EE97CA594341".fromHexToByteArray())
    private val expectedMnemonic =
        "moral fix coin subject there pact involve ceiling crowd urge bridge indicate pig swear tortoise staff divorce piano order tag lake coach artist denial"
            .split(" ")

    // FIXME
    @Test
    @Disabled("For some reason classloader can't find english.txt file")
    fun `should create mnemonic`() {
        // when
        val mnemonic = AttoMnemonics.seedToBip39(seed, AttoMnemonics.AttoMnemonicLanguageType.ENGLISH)

        // then
        assertEquals(expectedMnemonic, mnemonic)
    }

}