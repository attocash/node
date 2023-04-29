package atto.protocol.network.codec

import atto.commons.fromHexToAttoByteBuffer
import atto.commons.toHex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AttoNodeCodecTest {

    val codec = AttoNodeCodec()

    @Test
    fun `should serialize and deserialize`() {
        // given
        val expectedByteBuffer =
            "5854300000F9336EB9DB2D851D0AF6AF09B7EEAB24D839C040F2270B2BF622FA9CEA0DA80800000000000000000000FFFFAC1F10018A20020102000000".fromHexToAttoByteBuffer()

        // when
        val node = codec.fromByteBuffer(expectedByteBuffer)!!
        val byteBuffer = codec.toByteBuffer(node)

        // then
        assertEquals(expectedByteBuffer.toHex(), byteBuffer.toHex())
    }
}