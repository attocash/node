package atto.commons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AttoHashTest {

    @Test
    fun test() {
        // given
        val byteArray = "0000000000000000000000000000000000000000000000000000000000000000".fromHexToByteArray()

        // when
        val hash = AttoHash.hash(32, byteArray).value.toHex()

        // then
        val expectedHash = "89EB0D6A8A691DAE2CD15ED0369931CE0A949ECAFA5C3F93F8121833646E15C3"
        assertEquals(expectedHash, hash)
    }
}