package cash.atto.node.convertion

import cash.atto.commons.AttoVersion
import org.springframework.stereotype.Component

@Component
class AttoVersionToShortSerializerDBConverter : DBConverter<AttoVersion, Short> {
    override fun convert(source: AttoVersion): Short = source.value.toShort()
}

@Component
class ShortToAttoVersionDeserializerDBConverter : DBConverter<Short, AttoVersion> {
    override fun convert(source: Short): AttoVersion = AttoVersion(source.toUShort())
}

@Component
class IntegerToAttoVersionDeserializerDBConverter : DBConverter<Integer, AttoVersion> {
    override fun convert(source: Integer): AttoVersion = AttoVersion(source.toShort().toUShort())
}


