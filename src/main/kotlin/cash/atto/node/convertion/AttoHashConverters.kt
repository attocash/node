package cash.atto.node.convertion

import cash.atto.commons.AttoHash
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
@ConfigurationPropertiesBinding
class AttoHashConverter : Converter<String, AttoHash> {
    override fun convert(source: String): AttoHash = AttoHash.parse(source)
}

@Component
class AttoHashSerializerDBConverter : DBConverter<AttoHash, ByteArray> {
    override fun convert(source: AttoHash): ByteArray = source.value
}

@Component
class AttoHashDeserializerDBConverter : DBConverter<ByteArray, AttoHash> {
    override fun convert(source: ByteArray): AttoHash = AttoHash(source)
}
