package cash.atto.node.bootstrap.discovery

// The SnaKt proof in verification/snakt uses the inlined production body.
@Suppress("NOTHING_TO_INLINE")
internal inline fun discoveryChannelCapacity(
    capacity: Int,
    headroom: Int,
): Int = capacity + headroom
