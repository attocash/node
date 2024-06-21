package atto.node.convertion

import cash.atto.commons.AttoWork
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
@ConfigurationPropertiesBinding
object AttoWorkConverter : Converter<String, AttoWork> {
    override fun convert(source: String): AttoWork = AttoWork.parse(source)
}

@Component
class AttoWorkSerializerDBConverter : DBConverter<AttoWork, ByteArray> {
    override fun convert(source: AttoWork): ByteArray = source.value
}

@Component
class AttoWorkDeserializerDBConverter : DBConverter<ByteArray, AttoWork> {
    override fun convert(source: ByteArray): AttoWork = AttoWork(source)
}
