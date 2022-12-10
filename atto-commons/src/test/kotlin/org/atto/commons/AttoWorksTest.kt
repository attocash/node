package org.atto.commons

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal class AttoWorksTest {
    private val hash = AttoHash("0000000000000000000000000000000000000000000000000000000000000000".fromHexToByteArray())

    @Test
    fun `should perform work`() {
        val network = AttoNetwork.LOCAL
        val timestamp = AttoNetwork.INITIAL_INSTANT
        val work = AttoWorks.work(network, timestamp, hash)
        println(work)
        assertTrue(AttoWorks.isValid(network, timestamp, hash, work))
    }

    @Test
    fun `should validate work`() {
        val work = AttoWork("a6e485b8c9cfc073".fromHexToByteArray())
        assertTrue(work.isValid(AttoNetwork.LIVE, AttoNetwork.INITIAL_INSTANT, hash))
    }

    @Test
    fun `should not validate when work is below threshold`() {
        val work = AttoWork("a6e485b8c9cfc073".fromHexToByteArray())
        val timestamp =
            OffsetDateTime.of(AttoNetwork.INITIAL_DATE.plusYears(4), LocalTime.MIN, ZoneOffset.UTC).toInstant()
        assertFalse(work.isValid(AttoNetwork.LIVE, timestamp, hash))
    }
}