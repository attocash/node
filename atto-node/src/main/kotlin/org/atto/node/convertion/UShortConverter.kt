package org.atto.node.convertion

import org.springframework.stereotype.Component

@Component
class UShortSerializerDBConverter : DBConverter<UShort, Short> {

    override fun convert(source: UShort): Short {
        return source.toShort();
    }

}

@Component
class UShortDeserializerDBConverter : DBConverter<Short, UShort> {
    override fun convert(source: Short): UShort {
        return source.toUShort()
    }

}