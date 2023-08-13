package cash.atto.commons

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

internal class AttoSignatureTest {
    private val privateKey = AttoPrivateKey("00".repeat(32).fromHexToByteArray())
    private val publicKey = privateKey.toPublicKey()
    private val hash = AttoHash("0000000000000000000000000000000000000000000000000000000000000000".fromHexToByteArray())
    private val expectedSignature =
        AttoSignature("3DA1EBDFA96EDD181DBE3659D1C051C431F056A5AD6A97A60D5CCA10460438783546461E31285FC59F91C7072642745061E2451D5FF33BCCD8C3C74DABCAF60A".fromHexToByteArray())


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