package cash.atto.protocol.network.peer

import cash.atto.commons.serialiazers.protobuf.AttoProtobuf
import kotlinx.serialization.ExperimentalSerializationApi
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
        val byteArray = AttoProtobuf.encodeToByteArray(AttoKeepAlive.serializer(), expectedMessage)
        val message = AttoProtobuf.decodeFromByteArray(AttoKeepAlive.serializer(), byteArray)

        // then
        assertEquals(expectedMessage, message)
    }
}
