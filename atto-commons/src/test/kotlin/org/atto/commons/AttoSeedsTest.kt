package org.atto.commons

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class AttoSeedsTest {

    @Test
    fun `should generate seed`() {
        AttoSeeds.generateSeed()
    }

    @Test
    fun `should not instantiate invalid seed`() {
        assertThrows(IllegalArgumentException::class.java) { AttoSeed(ByteArray(31)) }
    }
}