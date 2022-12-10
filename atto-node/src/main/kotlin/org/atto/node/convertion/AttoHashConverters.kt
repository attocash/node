package org.atto.node.convertion

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import org.atto.commons.AttoHash
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
@ConfigurationPropertiesBinding
class AttoHashConverter : Converter<String, AttoHash> {
    override fun convert(source: String): AttoHash {
        return AttoHash.parse(source)
    }
}

@Component
class AttoHashStdSerializer : StdSerializer<AttoHash>(AttoHash::class.java) {
    override fun serialize(value: AttoHash, generator: JsonGenerator, provider: SerializerProvider) {
        generator.writeString(value.toString())
    }
}

@Component
class AttoHashStdDeserializer : StdDeserializer<AttoHash>(AttoHash::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): AttoHash {
        val value = parser.readValueAs(String::class.java)
        return AttoHash.parse(value)
    }
}


@Component
class AttoHashSerializerDBConverter : DBConverter<AttoHash, ByteArray> {
    override fun convert(source: AttoHash): ByteArray {
        return source.value;
    }
}

@Component
class AttoHashDeserializerDBConverter : DBConverter<ByteArray, AttoHash> {
    override fun convert(source: ByteArray): AttoHash {
        return AttoHash(source)
    }
}
