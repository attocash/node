package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

@OptIn(ExperimentalSerializationApi::class)
class AttoKeepAliveTest {
    @Test
    fun `should serialize`() {
        // given
        val expectedMessage = AttoKeepAlive(URI("ws://localhost:8081"))

        // when
        val byteArray = ProtoBuf.encodeToByteArray(AttoKeepAlive.serializer(), expectedMessage)
        val message = ProtoBuf.decodeFromByteArray(AttoKeepAlive.serializer(), byteArray)

        // then
        assertEquals(expectedMessage, message)
    }

    @Test
    fun `should accept neighbour without path`() {
        val message = AttoKeepAlive(URI("ws://localhost:8081"))

        assertTrue(message.isValid(AttoNetwork.LOCAL))
    }

    @Test
    fun `should reject neighbour with path`() {
        val message = AttoKeepAlive(URI("ws://localhost:8081/path"))

        assertFalse(message.isValid(AttoNetwork.LOCAL))
    }
}
