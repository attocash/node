package org.atto.commons

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AttoAddressesTest {
    private val seed = AttoSeed("1234567890123456789012345678901234567890123456789012345678901234".fromHexToByteArray())
    private val expectedAccount = AttoAddress("atto_3iwi45me3cgo9aza9wx5f7rder37hw11xtc1ek8psqxw5oxb8cujzue6p7nc")

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
    fun `should public key from account when hex starts with zero`() {
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