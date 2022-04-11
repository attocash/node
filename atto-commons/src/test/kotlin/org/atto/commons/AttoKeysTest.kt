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
        val expectedPublicKey = "19D3D919475DEED4696B5D13018151D1AF88B2BD3BCFF048B45031C1F36D1858"
        assertEquals(expectedPublicKey, publicKey.value.toHex())
    }
}