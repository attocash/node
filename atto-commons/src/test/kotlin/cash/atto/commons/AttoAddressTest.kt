package cash.atto.commons

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AttoAddressTest {
    private val expectedAccount =
        cash.atto.commons.AttoAddress("atto_3iwi45me3cgo9aza9wx5f7rder37hw11xtc1ek8psqxw5oxb8cujzue6p7nc")

    @Test
    fun `should create account`() {
        // given
        val publicKey =
            AttoPublicKey("C39010E6C0A9D53A3E83F3A36970B660257F000EE940648D6CDFBC1D7A932B71".fromHexToByteArray())

        // when
        val account = publicKey.toAddress()

        // then
        assertEquals(expectedAccount, account)
    }

    @Test
    fun `should create public key from account when hex starts with zero`() {
        // given
        val expectedPublicKey =
            AttoPublicKey("094870F534550D3E468AE385BF653BAFCEA889E4AFCABF6D69E155CF99BE6764".fromHexToByteArray())

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
            cash.atto.commons.AttoAddress(wrongAccount)
        }
    }

    @Test
    fun `should throw illegal argument exception when checksum doesn't match`() {
        // given
        val wrongAccount = expectedAccount.value.substring(0, expectedAccount.value.length - 1) + "9"

        Assertions.assertThrows(IllegalArgumentException::class.java) {
            cash.atto.commons.AttoAddress(wrongAccount)
        }
    }
}