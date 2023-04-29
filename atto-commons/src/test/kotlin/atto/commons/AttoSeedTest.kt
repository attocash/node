package atto.commons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class AttoSeedTest {

    @Test
    fun `should crete seed from mnemonic`() {
        // given
        val mnemonic =
            AttoMnemonic("edge defense waste choose enrich upon flee junk siren film clown finish luggage leader kid quick brick print evidence swap drill paddle truly occur")

        // when
        val seed = mnemonic.toSeed("some password")

        // then
        assertEquals(
            "0DC285FDE768F7FF29B66CE7252D56ED92FE003B605907F7A4F683C3DC8586D34A914D3C71FC099BB38EE4A59E5B081A3497B7A323E90CC68F67B5837690310C",
            seed.value.toHex()
        )

    }

    @Test
    fun `should not instantiate invalid seed`() {
        assertThrows(IllegalArgumentException::class.java) { AttoSeed(ByteArray(31)) }
    }
}