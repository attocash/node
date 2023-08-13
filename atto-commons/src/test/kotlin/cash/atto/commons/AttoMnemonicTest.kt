package cash.atto.commons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AttoMnemonicTest {

    @Test
    fun `should create mnemonic`() {
        // given
        val expectedMnemonic =
            AttoMnemonic("edge defense waste choose enrich upon flee junk siren film clown finish luggage leader kid quick brick print evidence swap drill paddle truly occur")

        // when
        val entropy = expectedMnemonic.toEntropy()
        val mnemonic = AttoMnemonic(entropy)

        // then
        assertEquals(expectedMnemonic.words.joinToString(" "), mnemonic.words.joinToString(" "))

    }

    @Test
    fun `should generate mnemonic`() {
        AttoMnemonic.generate()
    }

    @Test
    fun `should throw exception when mnemonic has invalid size`() {
        assertThrows<AttoMnemonicException> { AttoMnemonic("edge") }
    }

    @Test
    fun `should throw exception when mnemonic has invalid word`() {
        assertThrows<AttoMnemonicException> { AttoMnemonic("atto") }
    }

    @Test
    fun `should throw exception when mnemonic has invalid checksum`() {
        assertThrows<AttoMnemonicException> { AttoMnemonic("edge defense waste choose enrich upon flee junk siren film clown finish luggage leader kid quick brick print evidence swap drill paddle truly truly") }
    }

}