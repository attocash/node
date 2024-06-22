package cash.atto.node.convertion

import cash.atto.commons.AttoSignature
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
@ConfigurationPropertiesBinding
class AttoSignatureConverter : Converter<String, AttoSignature> {
    override fun convert(source: String): AttoSignature = AttoSignature.parse(source)
}

@Component
class AttoSignatureSerializerDBConverter : DBConverter<AttoSignature, ByteArray> {
    override fun convert(source: AttoSignature): ByteArray = source.value
}

@Component
class AttoSignatureDeserializerDBConverter : DBConverter<ByteArray, AttoSignature> {
    override fun convert(source: ByteArray): AttoSignature = AttoSignature(source)
}
