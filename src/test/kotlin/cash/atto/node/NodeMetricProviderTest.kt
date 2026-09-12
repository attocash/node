package cash.atto.node

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.protocol.AttoNode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.net.URI

class NodeMetricProviderTest {
    @Test
    fun `preserves semantic version in metric tag`() {
        // Given
        val registry = SimpleMeterRegistry()
        val provider = NodeMetricProvider(node(), "7.0.2-SNAPSHOT", registry)

        // When
        provider.start()

        // Then
        val gauge = registry.find("node.version").tag("version", "7.0.2").gauge()
        assertNotNull(gauge)
        assertEquals(1.0, gauge?.value())
    }

    @Test
    fun `preserves local version in metric tag`() {
        // Given
        val registry = SimpleMeterRegistry()
        val provider = NodeMetricProvider(node(), "local", registry)

        // When
        provider.start()

        // Then
        val gauge = registry.find("node.version").tag("version", "local").gauge()
        assertNotNull(gauge)
        assertEquals(1.0, gauge?.value())
    }

    private fun node(): AttoNode =
        mockk {
            every { publicUri } returns URI("ws://localhost:8080")
            every { network } returns AttoNetwork.LOCAL
            every { publicKey } returns AttoPublicKey(ByteArray(32))
            every { algorithm } returns AttoAlgorithm.V1
            every { features } returns emptySet()
        }
}
