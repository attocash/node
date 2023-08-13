package atto.node.convertion

import cash.atto.commons.AttoAmount
import org.springframework.stereotype.Component

@Component
class AttoAmountSerializerDBConverter : DBConverter<AttoAmount, Long> {

    override fun convert(source: AttoAmount): Long {
        return source.raw.toLong()
    }

}

@Component
class AttoAmountDeserializerDBConverter : DBConverter<Long, AttoAmount> {
    override fun convert(source: Long): AttoAmount {
        return AttoAmount(source.toULong())
    }

}