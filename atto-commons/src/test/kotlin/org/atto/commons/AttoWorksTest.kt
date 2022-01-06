package org.atto.commons

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class AttoWorksTest {
    private val hash = AttoHash("B15A71DD8E3A4B3F652B7E4742A622D27D00FFBBBA4CB715044B1E6499A6FB7E".fromHexToByteArray())

    @Test
    fun `should perform work`() {
        val work = AttoWorks.work(hash, AttoNetwork.LOCAL)
        assertTrue(AttoWorks.isValid(hash, work, AttoNetwork.LOCAL))
    }

    @Test
    fun `should validate work`() {
        val work = AttoWork("74bc36b683b5f525".fromHexToByteArray())
        assertTrue(AttoWorks.isValid(hash, work, AttoNetwork.LIVE))
    }

    @Test
    fun `should not validate when work is below threshold`() {
        val devWork = AttoWork("b04194bc13ae4e0c".fromHexToByteArray())
        assertFalse(AttoWorks.isValid(hash, devWork, AttoNetwork.LIVE))
    }
}