package cash.atto.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
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
}
