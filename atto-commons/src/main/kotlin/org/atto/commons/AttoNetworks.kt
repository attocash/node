package org.atto.commons

import java.time.*
import kotlin.math.pow

enum class AttoNetwork(val environment: String, private val difficultReductionFactor: ULong) {
    LIVE("XT0", 1u),
    BETA("XT1", 10u),
    DEV("XT2", 100u),
    LOCAL("XT3", 1_000u),

    UNKNOWN("???", ULong.MAX_VALUE);

    companion object {
        val INITIAL_LIVE_THRESHOLD = (-0x800_000_000L).toULong()
        val INITIAL_DATE: LocalDate = LocalDate.of(2023, 1, 1)
        val INITIAL_INSTANT: Instant = OffsetDateTime.of(INITIAL_DATE, LocalTime.MIN, ZoneOffset.UTC).toInstant()

        private val map = values().associateBy { it.environment }
        fun from(environment: String): AttoNetwork {
            return map.getOrDefault(environment, UNKNOWN)
        }
    }

    /**
     * Consider moving it AttoWorks
     */
    fun getThreshold(timestamp: Instant): ULong {
        //TODO uncomment
//        if (timestamp < INITIAL_INSTANT) {
//            throw IllegalArgumentException("Timestamp($timestamp) lower than initialInstant($INITIAL_INSTANT)");
//        }

        val years = timestamp.atZone(ZoneOffset.UTC).year - INITIAL_DATE.year
        val increaseFactor = (2.0).pow(years / 2).toULong() + 1UL // the +1 is just until next year

        val initialDifficult = (ULong.MAX_VALUE - INITIAL_LIVE_THRESHOLD) * difficultReductionFactor
        val difficult = initialDifficult / increaseFactor

        return ULong.MAX_VALUE - difficult
    }
}