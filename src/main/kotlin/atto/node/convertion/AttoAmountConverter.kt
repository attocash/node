package atto.node.convertion

import atto.node.toBigInteger
import atto.node.toULong
import cash.atto.commons.AttoAmount
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import org.springframework.stereotype.Component
import java.math.BigInteger


@Component
class AttoAmountStdSerializer : StdSerializer<AttoAmount>(AttoAmount::class.java) {
    override fun serialize(value: AttoAmount, generator: JsonGenerator, provider: SerializerProvider) {
        generator.writeString(value.toString())
    }
}

@Component
class AttoAmountStdDeserializer : StdDeserializer<AttoAmount>(AttoAmount::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): AttoAmount {
        val value = parser.readValueAs(String::class.java)
        return AttoAmount(value.toULong())
    }
}

@Component
class AttoAmountToBigIntegerSerializerDBConverter : DBConverter<AttoAmount, BigInteger> {
    override fun convert(source: AttoAmount): BigInteger {
        return source.raw.toBigInteger()
    }
}

@Component
class BigIntegerToAttoAmountDeserializerDBConverter : DBConverter<BigInteger, AttoAmount> {
    override fun convert(source: BigInteger): AttoAmount {
        return AttoAmount(source.toULong())
    }
}