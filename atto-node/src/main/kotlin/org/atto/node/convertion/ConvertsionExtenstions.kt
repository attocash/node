package org.atto.node.convertion

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset


/**
 * Workaround for H2. See https://github.com/r2dbc/r2dbc-h2/issues/238
 */
internal fun Instant.toLocalDateTime(): LocalDateTime {
    return LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}

internal fun LocalDateTime.toInstant(): Instant {
    return this.toInstant(ZoneOffset.UTC)
}