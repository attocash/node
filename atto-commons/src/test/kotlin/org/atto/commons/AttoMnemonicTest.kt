package org.atto.commons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AttoMnemonicTest {
    private val expectedSeed =
        AttoSeed("8FAB00B5EBFE073D5D8927345DEC6F397A49B73966A040148270EE97CA594341".fromHexToByteArray())
    private val expectedMnemonic =
        AttoMnemonic("moral fix coin subject there pact involve ceiling crowd urge bridge indicate pig swear tortoise staff divorce piano order tag lake coach artist denial")

    @Test
    fun `should create mnemonic`() {
        // when
        val mnemonic = expectedSeed.toMnemonic()
        val seed = mnemonic.toSeed()

        // then
        assertEquals(expectedMnemonic, mnemonic)
        assertEquals(expectedSeed, seed)
    }

}