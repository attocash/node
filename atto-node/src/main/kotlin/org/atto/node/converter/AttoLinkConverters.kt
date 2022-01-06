package org.atto.node.converter

//import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import org.atto.commons.AttoLink
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
@ConfigurationPropertiesBinding
class AttoLinkConverter : Converter<String, AttoLink> {
    override fun convert(source: String): AttoLink {
        return AttoLink.parse(source)
    }
}

@Component
class AttoLinkStdSerializer : StdSerializer<AttoLink>(AttoLink::class.java) {
    override fun serialize(value: AttoLink, generator: JsonGenerator, provider: SerializerProvider) {
        generator.writeString(value.toString())
    }
}

@Component
class AttoLinkStdDeserializer : StdDeserializer<AttoLink>(AttoLink::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): AttoLink {
        val value = parser.readValueAs(String::class.java)
        return AttoLink.parse(value)
    }
}

