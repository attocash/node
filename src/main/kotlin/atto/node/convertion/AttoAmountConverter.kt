package atto.node.convertion

import atto.node.toBigInteger
import atto.node.toULong
import cash.atto.commons.AttoAmount
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
