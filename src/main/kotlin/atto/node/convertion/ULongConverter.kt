package atto.node.convertion

import org.springframework.stereotype.Component

@Component
class ULongSerializerDBConverter : DBConverter<ULong, Long> {

    override fun convert(source: ULong): Long {
        return source.toLong();
    }

}

@Component
class ULongDeserializerDBConverter : DBConverter<Long, ULong> {
    override fun convert(source: Long): ULong {
        return source.toULong()
    }

}