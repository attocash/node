package cash.atto.node.convertion

import cash.atto.commons.AttoBlock
import cash.atto.commons.toBuffer
import kotlinx.io.readByteArray
import org.springframework.stereotype.Component

@Component
class AttoBlockSerializerDBConverter : DBConverter<AttoBlock, ByteArray> {
    override fun convert(source: AttoBlock): ByteArray = source.toBuffer().readByteArray()
}

@Component
class AttoBlockDeserializerDBConverter : DBConverter<ByteArray, AttoBlock> {
    override fun convert(source: ByteArray): AttoBlock = AttoBlock.fromBuffer(source.toBuffer())!!
}
