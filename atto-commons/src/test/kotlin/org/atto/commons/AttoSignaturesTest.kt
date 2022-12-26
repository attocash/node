package org.atto.commons

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

internal class AttoSignaturesTest {
    private val seed = AttoSeed("0000000000000000000000000000000000000000000000000000000000000000".fromHexToByteArray())
    private val privateKey = seed.toPrivateKey(0u)
    private val publicKey = privateKey.toPublicKey()
    private val hash = AttoHash("0000000000000000000000000000000000000000000000000000000000000000".fromHexToByteArray())
    private val expectedSignature =
        AttoSignature("624329512A3433895673A6A2C5179199D4DE014049E60AB19319847C626B0997A06C0DC9AF79F624925C2B1F05F42E40CDDCBC5B403CE339E2768DB953E09201".fromHexToByteArray())


    @Test
    fun `should sign`() {
        // when
        val signature = privateKey.sign(hash)

        // then
        assertEquals(expectedSignature, signature)
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
        val hash16 = AttoHash(Random.nextBytes(ByteArray(16)), 16)

        // when
        val signature = privateKey.sign(hash16)

        // then
        assertTrue(signature.isValid(publicKey, hash16))
    }
}