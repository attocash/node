package cash.atto.node.bootstrap.discovery

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DiscoveryPropertiesTest {
    @Test
    fun `accepts channel capacity just below the unlimited sentinel`() {
        // Given
        val properties = DiscoveryProperties()
        properties.capacity = Int.MAX_VALUE - 2
        properties.headroom = 1

        // When and then
        properties.validate()
    }

    @Test
    fun `rejects the unlimited channel capacity sentinel`() {
        // Given
        val properties = DiscoveryProperties()
        properties.capacity = Int.MAX_VALUE - 1
        properties.headroom = 1

        // When and then
        assertThrows<IllegalArgumentException> { properties.validate() }
    }

    @Test
    fun `rejects channel capacity that would overflow an integer`() {
        // Given
        val properties = DiscoveryProperties()
        properties.capacity = Int.MAX_VALUE
        properties.headroom = 1

        // When and then
        assertThrows<IllegalArgumentException> { properties.validate() }
    }
}
