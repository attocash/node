package cash.atto.node.bootstrap.discovery

import org.jetbrains.kotlin.formver.plugin.AlwaysVerify
import org.jetbrains.kotlin.formver.plugin.postconditions
import org.jetbrains.kotlin.formver.plugin.preconditions

@AlwaysVerify
internal fun verifyDiscoveryChannelCapacity(
    capacity: Int,
    headroom: Int,
): Int {
    // DiscoveryProperties validates these bounds before DiscoveryQueue allocates the channel.
    // The literal is Channel.UNLIMITED (Int.MAX_VALUE), which SnaKt cannot resolve as a qualified constant.
    preconditions {
        capacity > 0
        headroom >= 0
        capacity <= 2_147_483_647 - headroom
    }

    postconditions<Int> { result ->
        result >= capacity
        result <= 2_147_483_647
        result - capacity == headroom
        (result < 2_147_483_647) == (capacity < 2_147_483_647 - headroom)
    }

    return discoveryChannelCapacity(capacity, headroom)
}
