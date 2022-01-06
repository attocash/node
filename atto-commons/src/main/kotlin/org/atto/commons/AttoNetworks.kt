package org.atto.commons

enum class AttoNetwork(val environment: String, val threshold: ULong) {
    LIVE("XT0", (-0x800_000_000L).toULong()),
    BETA("XT1", (-0x4_000_000_000L).toULong()),
    DEV("XT2", (-0x100_000_000_000L).toULong()),
    LOCAL("XT3", (-0x1_000_000_000_000L).toULong()),

    UNKNOWN("???", ULong.MAX_VALUE);

    companion object {
        private val map = values().associateBy { it.environment }
        fun fromCode(environment: String): AttoNetwork {
            return map.getOrDefault(environment, UNKNOWN)
        }
    }
}