package atto.node.convertion

import atto.node.toBigInteger
import atto.node.toULong
import cash.atto.commons.AttoAmount
import org.springframework.stereotype.Component
import java.math.BigInteger

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

@Component
class AttoAmountToLongSerializerDBConverter : DBConverter<AttoAmount, Long> {

    override fun convert(source: AttoAmount): Long {
        return source.raw.toLong()
    }

}

@Component
class LongToAttoAmountDeserializerDBConverter : DBConverter<Long, AttoAmount> {
    override fun convert(source: Long): AttoAmount {
        return AttoAmount(source.toULong())
    }

}