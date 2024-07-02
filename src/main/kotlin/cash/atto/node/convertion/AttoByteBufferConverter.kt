package cash.atto.node.convertion

import cash.atto.commons.toBuffer
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.springframework.stereotype.Component

@Component
class BufferSerializerDBConverter : DBConverter<Buffer, ByteArray> {
    override fun convert(source: Buffer): ByteArray = source.readByteArray()
}

@Component
class BufferDeserializerDBConverter : DBConverter<ByteArray, Buffer> {
    override fun convert(source: ByteArray): Buffer = source.toBuffer()
}
