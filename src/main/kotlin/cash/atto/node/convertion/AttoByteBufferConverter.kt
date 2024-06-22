package cash.atto.node.convertion

import cash.atto.commons.AttoByteBuffer
import org.springframework.stereotype.Component

@Component
class AttoByteBufferSerializerDBConverter : DBConverter<AttoByteBuffer, ByteArray> {
    override fun convert(source: AttoByteBuffer): ByteArray = source.toByteArray()
}

@Component
class AttoByteBufferDeserializerDBConverter : DBConverter<ByteArray, AttoByteBuffer> {
    override fun convert(source: ByteArray): AttoByteBuffer = AttoByteBuffer.from(source)
}
