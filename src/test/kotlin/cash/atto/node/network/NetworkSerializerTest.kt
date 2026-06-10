package cash.atto.node.network

import cash.atto.commons.AttoNetwork
import cash.atto.protocol.AttoVotePush
import cash.atto.protocol.forgedVote
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class NetworkSerializerTest {
    @Test
    fun `should return null for invalid message`() {
        val serialized = NetworkSerializer.serialize(AttoVotePush(forgedVote()))

        val message = NetworkSerializer.deserialize(serialized, AttoNetwork.LOCAL)

        assertNull(message)
    }

    @Test
    fun `should return null for malformed message`() {
        val message = NetworkSerializer.deserialize(byteArrayOf(1, 2, 3), AttoNetwork.LOCAL)

        assertNull(message)
    }
}
