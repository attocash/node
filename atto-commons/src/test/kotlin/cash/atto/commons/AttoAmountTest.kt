package cash.atto.commons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AttoAmountTest {

    @Test
    fun sum() {
        // given
        val amount1 = AttoAmount(1u)

        // when
        val total = amount1 + amount1

        // then
        assertEquals(AttoAmount(2u), total)
    }

    @Test
    fun subtract() {
        // given
        val amount3 = AttoAmount(3u)
        val amount1 = AttoAmount(1u)

        // when
        val total = amount3 - amount1

        // then
        assertEquals(AttoAmount(2u), total)
    }

    @Test
    fun overflow() {
        try {
            AttoAmount.MAX + AttoAmount.MAX
        } catch (e: IllegalStateException) {
            assertEquals("ULong overflow", e.message)
        }
    }

    @Test
    fun underflow() {
        try {
            AttoAmount.MIN - AttoAmount.MAX
        } catch (e: IllegalStateException) {
            assertEquals("ULong underflow", e.message)
        }
    }

    @Test
    fun aboveMaxAmount() {
        try {
            AttoAmount.MAX + AttoAmount(1U)
        } catch (e: IllegalStateException) {
            assertEquals("18000000000000000001 exceeds the max amount of 18000000000000000000", e.message)
        }
    }
}