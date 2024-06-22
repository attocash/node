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
