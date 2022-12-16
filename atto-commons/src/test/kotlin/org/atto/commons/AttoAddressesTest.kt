package org.atto.commons

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AttoAddressesTest {
    private val seed = AttoSeed("0000000000000000000000000000000000000000000000000000000000000000".fromHexToByteArray())
    private val expectedAccount = AttoAddress("atto_33jppf5rfij877axrtp1q41j76wpynfccbgdowuxrh6x5hm9zezkgb9iiywf")

    @Test
    fun `should create account`() {
        // given
        val privateKey = seed.toPrivateKey(0u)
        val publicKey = privateKey.toPublicKey()

        // when
        val account = publicKey.toAddress()

        // then
        assertEquals(expectedAccount, account)
    }

    @Test
    fun `should create public key from account when hex starts with zero`() {
        // given
        val byteArray = "094870F534550D3E468AE385BF653BAFCEA889E4AFCABF6D69E155CF99BE6764".fromHexToByteArray()
        val expectedPublicKey = AttoPublicKey(byteArray)

        // when
        val publicKey = expectedPublicKey.toAddress().toPublicKey()

        // then
        assertEquals(expectedPublicKey, publicKey)
    }

    @Test
    fun `should extract address to public key`() {
        // when
        val publicKey = expectedAccount.toPublicKey()

        // then
        assertEquals(expectedAccount, publicKey.toAddress())
    }

    @Test
    fun `should throw illegal argument exception when regex doesn't match`() {
        // given
        val wrongAccount = expectedAccount.value.replace("atto_", "nano_")

        // when
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            AttoAddress(wrongAccount)
        }
    }

    @Test
    fun `should throw illegal argument exception when checksum doesn't match`() {
        // given
        val wrongAccount = expectedAccount.value.substring(0, expectedAccount.value.length - 1) + "9"

        Assertions.assertThrows(IllegalArgumentException::class.java) {
            AttoAddress(wrongAccount)
        }
    }
}