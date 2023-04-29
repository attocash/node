package atto.node.convertion

import atto.commons.AttoByteBuffer
import org.springframework.stereotype.Component

@Component
class AttoByteBufferSerializerDBConverter : DBConverter<AttoByteBuffer, ByteArray> {
    override fun convert(source: AttoByteBuffer): ByteArray {
        return source.toByteArray();
    }
}

@Component
class AttoByteBufferDeserializerDBConverter : DBConverter<ByteArray, AttoByteBuffer> {
    override fun convert(source: ByteArray): AttoByteBuffer {
        return AttoByteBuffer.from(source)
    }
}