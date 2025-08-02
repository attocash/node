package cash.atto.node.convertion

import cash.atto.commons.AttoHeight
import cash.atto.node.toBigInteger
import cash.atto.node.toULong
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.BigInteger

@Component
class AttoHeightToBigIntegerSerializerDBConverter : DBConverter<AttoHeight, BigInteger> {
    override fun convert(source: AttoHeight): BigInteger = source.value.toBigInteger()
}

@Component
class BigIntegerToAttoHeightDeserializerDBConverter : DBConverter<BigInteger, AttoHeight> {
    override fun convert(source: BigInteger): AttoHeight = AttoHeight(source.toULong())
}

@Component
class BigDecimalToAttoHeightDeserializerDBConverter : DBConverter<BigDecimal, AttoHeight> {
    override fun convert(source: BigDecimal): AttoHeight = AttoHeight(source.toBigInteger().toULong())
}

@Component
class AttoHeightToStringSerializerDBConverter : DBConverter<AttoHeight, String> {
    override fun convert(source: AttoHeight): String = source.value.toString()
}

@Component
class StringToAttoHeightDeserializerDBConverter : DBConverter<String, AttoHeight> {
    override fun convert(source: String): AttoHeight = AttoHeight(source.toULong())
}
