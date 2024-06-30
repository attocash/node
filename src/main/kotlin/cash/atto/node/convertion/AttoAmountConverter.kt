package cash.atto.node.convertion

import cash.atto.commons.AttoAmount
import cash.atto.node.toBigInteger
import cash.atto.node.toULong
import org.springframework.stereotype.Component
import java.math.BigInteger

@Component
class AttoAmountToBigIntegerSerializerDBConverter : DBConverter<AttoAmount, BigInteger> {
    override fun convert(source: AttoAmount): BigInteger = source.raw.toBigInteger()
}

@Component
class BigIntegerToAttoAmountDeserializerDBConverter : DBConverter<BigInteger, AttoAmount> {
    override fun convert(source: BigInteger): AttoAmount = AttoAmount(source.toULong())
}

@Component
class AttoAmountToStringSerializerDBConverter : DBConverter<AttoAmount, String> {
    override fun convert(source: AttoAmount): String = source.raw.toString()
}

@Component
class StringToAttoAmountDeserializerDBConverter : DBConverter<String, AttoAmount> {
    override fun convert(source: String): AttoAmount = AttoAmount(source.toULong())
}
