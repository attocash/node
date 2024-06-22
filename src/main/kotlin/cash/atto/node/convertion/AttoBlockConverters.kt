package cash.atto.node.convertion

import cash.atto.commons.AttoBlock
import cash.atto.commons.toAttoByteBuffer
import org.springframework.stereotype.Component

@Component
class AttoBlockSerializerDBConverter : DBConverter<AttoBlock, ByteArray> {
    override fun convert(source: AttoBlock): ByteArray = source.toByteBuffer().toByteArray()
}

@Component
class AttoBlockDeserializerDBConverter : DBConverter<ByteArray, AttoBlock> {
    override fun convert(source: ByteArray): AttoBlock = AttoBlock.fromByteBuffer(source.toAttoByteBuffer())!!
}
