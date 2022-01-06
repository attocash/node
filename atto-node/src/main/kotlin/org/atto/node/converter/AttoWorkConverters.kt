package org.atto.node.converter

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import org.atto.commons.AttoWork
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
@ConfigurationPropertiesBinding
object AttoWorkConverter : Converter<String, AttoWork> {
    override fun convert(source: String): AttoWork {
        return AttoWork.parse(source)
    }
}

@Component
object AttoWorkStdSerializer : StdSerializer<AttoWork>(AttoWork::class.java) {
    override fun serialize(value: AttoWork, generator: JsonGenerator, provider: SerializerProvider) {
        generator.writeString(value.toString())
    }
}

@Component
class AttoWorkStdDeserializer : StdDeserializer<AttoWork>(AttoWork::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): AttoWork {
        val value = parser.readValueAs(String::class.java)
        return AttoWork.parse(value)
    }
}

