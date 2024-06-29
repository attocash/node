package cash.atto.node.convertion

import cash.atto.commons.toBuffer
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.springframework.stereotype.Component

@Component
class AttoByteBufferSerializerDBConverter : DBConverter<Buffer, ByteArray> {
    override fun convert(source: Buffer): ByteArray = source.readByteArray()
}

@Component
class AttoByteBufferDeserializerDBConverter : DBConverter<ByteArray, Buffer> {
    override fun convert(source: ByteArray): Buffer = source.toBuffer()
}
