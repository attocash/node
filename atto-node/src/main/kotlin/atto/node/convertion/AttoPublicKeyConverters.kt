package atto.node.convertion

import cash.atto.commons.AttoPublicKey
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
@ConfigurationPropertiesBinding
class AttoPublicKeyConverter : Converter<String, AttoPublicKey> {
    override fun convert(source: String): AttoPublicKey {
        return AttoPublicKey.parse(source)
    }
}

@Component
class AttoPublicKeyStdSerializer : StdSerializer<AttoPublicKey>(AttoPublicKey::class.java) {
    override fun serialize(value: AttoPublicKey, generator: JsonGenerator, provider: SerializerProvider) {
        generator.writeString(value.toString())
    }
}

@Component
class AttoPublicKeyStdDeserializer : StdDeserializer<AttoPublicKey>(AttoPublicKey::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): AttoPublicKey {
        val value = parser.readValueAs(String::class.java)
        return AttoPublicKey.parse(value)
    }
}


@Component
class AttoPublicKeySerializerDBConverter : DBConverter<AttoPublicKey, ByteArray> {
    override fun convert(source: AttoPublicKey): ByteArray {
        return source.value;
    }
}

@Component
class AttoPublicKeyDeserializerDBConverter : DBConverter<ByteArray, AttoPublicKey> {
    override fun convert(source: ByteArray): AttoPublicKey {
        return AttoPublicKey(source)
    }
}
