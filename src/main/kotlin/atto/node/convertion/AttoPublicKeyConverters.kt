package atto.node.convertion

import cash.atto.commons.AttoPublicKey
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
@ConfigurationPropertiesBinding
class AttoPublicKeyConverter : Converter<String, AttoPublicKey> {
    override fun convert(source: String): AttoPublicKey = AttoPublicKey.parse(source)
}

@Component
class AttoPublicKeySerializerDBConverter : DBConverter<AttoPublicKey, ByteArray> {
    override fun convert(source: AttoPublicKey): ByteArray = source.value
}

@Component
class AttoPublicKeyDeserializerDBConverter : DBConverter<ByteArray, AttoPublicKey> {
    override fun convert(source: ByteArray): AttoPublicKey = AttoPublicKey(source)
}
