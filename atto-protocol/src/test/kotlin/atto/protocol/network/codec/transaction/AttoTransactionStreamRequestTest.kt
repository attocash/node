package atto.protocol.network.codec.transaction

import atto.protocol.transaction.AttoTransactionStreamRequest
import cash.atto.commons.AttoPublicKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AttoTransactionStreamRequestTest {
    val codec = AttoTransactionStreamRequestCodec()

    @Test
    fun `should serialize and deserialize`() {
        // given
        val publicKey = AttoPublicKey(ByteArray(32))
        val expectedRequest = AttoTransactionStreamRequest(publicKey, 1U, 10U)

        // when
        val byteBuffer = codec.toByteBuffer(expectedRequest)
        val request = codec.fromByteBuffer(byteBuffer)

        // then
        assertEquals(expectedRequest, request)

    }
}