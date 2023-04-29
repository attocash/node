package atto.commons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AttoKeyTest {

    @Test
    fun shouldCreatePrivateKey() {
        // given
        val mnemonic =
            AttoMnemonic("edge defense waste choose enrich upon flee junk siren film clown finish luggage leader kid quick brick print evidence swap drill paddle truly occur")
        val seed = mnemonic.toSeed("some password")

        // when
        val privateKey = seed.toPrivateKey(0U)

        // then
        val expectedPrivateKey = "38FDB3EBF6B34965FFEE18583B597808B56CDA98B074405A30152E2296616B3A"
        assertEquals(expectedPrivateKey, privateKey.value.toHex())
    }

    @Test
    fun shouldCreatePublicKey() {
        // given
        val mnemonic =
            AttoMnemonic("edge defense waste choose enrich upon flee junk siren film clown finish luggage leader kid quick brick print evidence swap drill paddle truly occur")
        val seed = mnemonic.toSeed("some password")
        val privateKey = seed.toPrivateKey(0U)

        // when
        val publicKey = privateKey.toPublicKey()

        // then
        val expectedPublicKey = "9979705D9F9588F46667697329947688E5FFC4DF36F5D0C6A4E29D023E7BF2CE"
        assertEquals(expectedPublicKey, publicKey.value.toHex())
    }
}