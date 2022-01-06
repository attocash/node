package org.atto.commons

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

internal class AttoSignaturesTest {
    private val seed = AttoSeed("1234567890123456789012345678901234567890123456789012345678901234".fromHexToByteArray())
    private val privateKey = seed.toPrivateKey(0u)
    private val publicKey = privateKey.toPublicKey()
    private val hash = "AEC75F807DCE45AFA787DE7B395BE498A885525569DD614162E0C80FD4F27EE9".fromHexToByteArray()
    private val expectedSignature =
        AttoSignature("E8B0FDA15BF0F8FC665BA094D75A62BBB204D35F01711214EB1290030F741D0C6602E38F41E8A727C99B40650A76C69AB689CAD8626B75A56AE9D2B75C073A0F".fromHexToByteArray())


    @Test
    fun `should sign`() {
        // when
        val signature = privateKey.sign(hash)

        // then
        assertTrue(expectedSignature.value.contentEquals(signature.value))
        assertTrue(expectedSignature.isValid(publicKey, hash))
    }

    @Test
    fun `should not validate wrong signature`() {
        // given
        val randomSignature = AttoSignature(Random.nextBytes(ByteArray(64)))

        // then
        assertFalse(randomSignature.isValid(publicKey, hash))
    }

    @Test
    fun `should sign 16 bytes`() {
        // given
        val hash16 = Random.nextBytes(ByteArray(16))

        // when
        val signature = privateKey.sign(hash16)

        // then
        assertTrue(signature.isValid(publicKey, hash16))
    }
}