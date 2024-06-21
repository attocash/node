package atto.node.convertion

import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Component
class ZonedDateTimeSerializerDBConverter : DBConverter<Instant, ZonedDateTime> {
    override fun convert(source: Instant): ZonedDateTime = source.atZone(ZoneOffset.UTC)
}

@Component
class ZonedDateTimeDeserializerDBConverter : DBConverter<ZonedDateTime, Instant> {
    override fun convert(source: ZonedDateTime): Instant = source.toInstant()
}
