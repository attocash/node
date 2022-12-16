package org.atto.commons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AttoKeysTest {

    @Test
    fun shouldCreatePrivateKey() {
        // given
        val byteArray = "0000000000000000000000000000000000000000000000000000000000000000".fromHexToByteArray()
        val seed = AttoSeed(byteArray)

        // when
        val privateKey = AttoKeys.toPrivateKey(seed, 0u)

        // then
        val expectedPrivateKey = "9F0E444C69F77A49BD0BE89DB92C38FE713E0963165CCA12FAF5712D7657120F"
        assertEquals(expectedPrivateKey, privateKey.value.toHex())
    }

    @Test
    fun shouldCreatePublicKey() {
        // given
        val byteArray = "0000000000000000000000000000000000000000000000000000000000000000".fromHexToByteArray()
        val privateKey = AttoPrivateKey(byteArray)

        // when
        val publicKey = privateKey.toPublicKey()

        // then
        val expectedPublicKey = "3B6A27BCCEB6A42D62A3A8D02A6F0D73653215771DE243A63AC048A18B59DA29"
        assertEquals(expectedPublicKey, publicKey.value.toHex())
    }
}